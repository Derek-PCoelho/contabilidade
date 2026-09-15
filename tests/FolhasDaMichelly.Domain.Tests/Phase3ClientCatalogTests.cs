using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Common;

namespace FolhasDaMichelly.Domain.Tests;

public sealed class Phase3ClientCatalogTests
{
    private static readonly Guid OrganizationId = Guid.Parse("11111111-1111-1111-1111-111111111111");
    private static readonly Guid UserId = Guid.Parse("22222222-2222-2222-2222-222222222222");
    private static readonly DateTimeOffset Now = new(2026, 8, 20, 12, 0, 0, TimeSpan.Zero);

    [Theory]
    [InlineData("529.982.247-25", "52998224725")]
    [InlineData("11.222.333/0001-81", "11222333000181")]
    public void BrazilianRegistrationNormalizesSyntheticValidDocuments(
        string input,
        string expected)
    {
        var actual = expected.Length == 11
            ? BrazilianRegistration.NormalizeCpf(input)
            : BrazilianRegistration.NormalizeCnpj(input);

        Assert.Equal(expected, actual);
    }

    [Theory]
    [InlineData("111.111.111-11")]
    [InlineData("11.222.333/0001-82")]
    [InlineData("52998224725<script>")]
    public void BrazilianRegistrationRejectsInvalidOrMalformedDocuments(string input)
    {
        Assert.Throws<DomainValidationException>(() =>
        {
            _ = input.Contains('/', StringComparison.Ordinal)
                ? BrazilianRegistration.NormalizeCnpj(input)
                : BrazilianRegistration.NormalizeCpf(input);
        });
    }

    [Fact]
    public void IndividualCannotContainEstablishments()
    {
        var draft = IndividualDraft() with
        {
            Establishments =
            [
                new EstablishmentDraft(
                    Guid.Empty,
                    "11.222.333/0001-81",
                    "Empresa sintética",
                    "Matriz",
                    null,
                    true,
                    false),
            ],
        };

        Assert.Throws<DomainValidationException>(() =>
            new Client(Guid.NewGuid(), OrganizationId, draft, UserId, Now));
    }

    [Fact]
    public void IndividualCannotContainCompanyPartners()
    {
        var draft = IndividualDraft() with
        {
            Partners =
            [
                new ClientPartnerDraft(
                    Guid.Empty,
                    "Representante sintético",
                    "529.982.247-25",
                    ClientPartnerRole.LegalRepresentative,
                    true),
            ],
        };

        Assert.Throws<DomainValidationException>(() =>
            new Client(Guid.NewGuid(), OrganizationId, draft, UserId, Now));
    }

    [Fact]
    public void PartnerCpfIsNormalizedButNeverBecomesAClientIdentifier()
    {
        var draft = LegalEntityDraft() with
        {
            Partners =
            [
                new ClientPartnerDraft(
                    Guid.Empty,
                    "Sócia-administradora sintética",
                    "529.982.247-25",
                    ClientPartnerRole.ManagingPartner,
                    true),
            ],
        };

        var client = new Client(Guid.NewGuid(), OrganizationId, draft, UserId, Now);

        var partner = Assert.Single(client.Partners);
        Assert.Equal("52998224725", partner.CpfNormalized);
        Assert.Null(partner.EmailNormalized);
        Assert.Empty(client.Identifiers);
    }

    [Fact]
    public void PartnerEmailIsOptionalAndUsesRecipientNormalizationPolicy()
    {
        var draft = LegalEntityDraft() with
        {
            Partners =
            [
                new ClientPartnerDraft(
                    Guid.Empty,
                    "Sócia-administradora sintética",
                    null,
                    ClientPartnerRole.ManagingPartner,
                    true,
                    " Socia.Administradora@Example.INVALID "),
            ],
        };

        var client = new Client(Guid.NewGuid(), OrganizationId, draft, UserId, Now);

        var partner = Assert.Single(client.Partners);
        Assert.Null(partner.CpfNormalized);
        Assert.Equal("socia.administradora@example.invalid", partner.EmailNormalized);
    }

    [Fact]
    public void PartnerEmailIsValidatedOnlyWhenProvided()
    {
        var draft = LegalEntityDraft() with
        {
            Partners =
            [
                new ClientPartnerDraft(
                    Guid.Empty,
                    "Representante sintético",
                    null,
                    ClientPartnerRole.LegalRepresentative,
                    true,
                    "not-an-email"),
            ],
        };

        Assert.Throws<DomainValidationException>(() =>
            new Client(Guid.NewGuid(), OrganizationId, draft, UserId, Now));
    }

    [Theory]
    [InlineData("nome@dominio")]
    [InlineData("nome@-empresa.com")]
    [InlineData("nome@empresa..com")]
    [InlineData("Nome <nome@example.com>")]
    [InlineData("nome@@example.com")]
    public void EmailValidationRejectsIncompleteOrAmbiguousAddresses(string value)
    {
        Assert.False(EmailAddress.TryNormalize(value, out _));
        Assert.Throws<DomainValidationException>(() => EmailAddress.Normalize(value));
    }

    [Theory]
    [InlineData(" Financeiro+folha@Example.COM ", "financeiro+folha@example.com")]
    [InlineData("pessoa@example.invalid", "pessoa@example.invalid")]
    public void EmailValidationAcceptsCompleteAddresses(string value, string expected)
    {
        Assert.True(EmailAddress.TryNormalize(value, out var normalized));
        Assert.Equal(expected, normalized);
    }

    [Fact]
    public void LegalEntityCannotPromotePartnerCpfToClientIdentifier()
    {
        var draft = LegalEntityDraft() with
        {
            Identifiers =
            [
                new ClientIdentifierDraft(
                    Guid.Empty,
                    ClientIdentifierType.Cpf,
                    "529.982.247-25",
                    ClientIdentifierSemanticRole.PrimaryTaxpayer,
                    1,
                    true,
                    true),
            ],
        };

        Assert.Throws<DomainValidationException>(() =>
            new Client(Guid.NewGuid(), OrganizationId, draft, UserId, Now));
    }

    [Fact]
    public void LegalEntityRequiresEstablishmentsFromSameCnpjRoot()
    {
        var draft = LegalEntityDraft() with
        {
            Establishments =
            [
                new EstablishmentDraft(
                    Guid.Empty,
                    "04.252.011/0001-10",
                    "Outra raiz sintética",
                    "Outra raiz",
                    null,
                    true,
                    true),
            ],
        };

        Assert.Throws<DomainValidationException>(() =>
            new Client(Guid.NewGuid(), OrganizationId, draft, UserId, Now));
    }

    [Fact]
    public void RecipientEmailAndValidityAreValidated()
    {
        var draft = LegalEntityDraft() with
        {
            Recipients =
            [
                new RecipientDraft(
                    Guid.Empty,
                    null,
                    "Financeiro sintético",
                    "not-an-email",
                    DeliveryRole.To,
                    null,
                    true,
                    true,
                    new DateOnly(2026, 9, 1),
                    new DateOnly(2026, 8, 1)),
            ],
        };

        Assert.Throws<DomainValidationException>(() =>
            new Client(Guid.NewGuid(), OrganizationId, draft, UserId, Now));
    }

    [Fact]
    public void InactiveClientHasExplicitOperationalBlockAndRemainsReadable()
    {
        var client = new Client(Guid.NewGuid(), OrganizationId, LegalEntityDraft(), UserId, Now);
        client.Apply(
            LegalEntityDraft() with { IsActive = false },
            client.Version,
            UserId,
            Now.AddMinutes(1));

        var readiness = ClientOperationalReadinessEvaluator.Evaluate(
            client,
            new DateOnly(2026, 8, 20));

        Assert.False(readiness.IsEligible);
        Assert.Contains(ClientBlockCodes.ClientInactive, readiness.BlockCodes);
        Assert.Equal("Cliente sintético PJ", client.LegalNameOrFullName);
    }

    [Fact]
    public void ClientAndTemplateRejectStaleVersions()
    {
        var client = new Client(Guid.NewGuid(), OrganizationId, LegalEntityDraft(), UserId, Now);
        var template = new MessageTemplate(
            Guid.NewGuid(),
            OrganizationId,
            new MessageTemplateDraft(
                client.Id,
                null,
                "Modelo sintético",
                "Assunto {{competencia}}",
                "Corpo sem envio",
                SignatureMode.Organization,
                true,
                true),
            UserId,
            Now);

        Assert.Throws<ConcurrencyConflictException>(() =>
            client.Apply(LegalEntityDraft(), 0, UserId, Now.AddMinutes(1)));
        Assert.Throws<ConcurrencyConflictException>(() =>
            template.Apply(
                new MessageTemplateDraft(
                    client.Id,
                    null,
                    "Modelo sintético",
                    "Outro assunto",
                    "Outro corpo",
                    SignatureMode.None,
                    false,
                    true),
                0,
                UserId,
                Now.AddMinutes(1)));
    }

    [Theory]
    [InlineData(161)]
    [InlineData(20_000)]
    public void MessageTemplateAcceptsBodyLongerThanNameLimitUpToTwentyThousandCharacters(int length)
    {
        var body = new string('x', length);

        var template = new MessageTemplate(
            Guid.NewGuid(),
            OrganizationId,
            new MessageTemplateDraft(
                null,
                null,
                "Modelo sintético",
                "Assunto sintético",
                body,
                SignatureMode.Organization,
                true,
                true),
            UserId,
            Now);

        Assert.Equal(length, template.BodyTemplate.Length);
    }

    [Fact]
    public void MessageTemplateRejectsBodyAboveTwentyThousandCharacters()
    {
        var draft = new MessageTemplateDraft(
            null,
            null,
            "Modelo sintético",
            "Assunto sintético",
            new string('x', 20_001),
            SignatureMode.Organization,
            true,
            true);

        Assert.Throws<DomainValidationException>(() =>
            new MessageTemplate(Guid.NewGuid(), OrganizationId, draft, UserId, Now));
    }

    private static ClientCatalogDraft IndividualDraft() => new(
        PersonType.Individual,
        "Pessoa sintética",
        "Pessoa teste",
        "PF-001",
        "529.982.247-25",
        true,
        null,
        null,
        "Dados exclusivamente sintéticos.",
        [],
        [],
        []);

    private static ClientCatalogDraft LegalEntityDraft() => new(
        PersonType.LegalEntity,
        "Cliente sintético PJ",
        "Cliente teste",
        "PJ-001",
        "11.222.333/0001-81",
        true,
        null,
        null,
        "Dados exclusivamente sintéticos.",
        [],
        [
            new EstablishmentDraft(
                Guid.Empty,
                "11.222.333/0001-81",
                "Cliente sintético PJ",
                "Matriz sintética",
                "MAT-001",
                true,
                true),
        ],
        [
            new RecipientDraft(
                Guid.Empty,
                null,
                "Financeiro sintético",
                "financeiro@example.invalid",
                DeliveryRole.To,
                null,
                true,
                true,
                null,
                null),
        ]);
}
