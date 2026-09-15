namespace FolhasDaMichelly.Domain.Clients;

public static class ClientBlockCodes
{
    public const string ClientInactive = "CLIENT_INACTIVE";
    public const string NoActiveToRecipient = "NO_ACTIVE_TO_RECIPIENT";
    public const string PartnerEmailInvalid = "PARTNER_EMAIL_INVALID";
    public const string RecipientEmailInvalid = "RECIPIENT_EMAIL_INVALID";
}

public sealed record ClientOperationalReadiness(bool IsEligible, IReadOnlyList<string> BlockCodes);

public static class ClientOperationalReadinessEvaluator
{
    public static ClientOperationalReadiness Evaluate(Client client, DateOnly today)
    {
        ArgumentNullException.ThrowIfNull(client);
        var blocks = new List<string>();
        if (!client.IsActive)
        {
            blocks.Add(ClientBlockCodes.ClientInactive);
        }

        if (!client.Recipients.Any(recipient =>
                recipient.DeliveryRole == DeliveryRole.To && recipient.IsCurrentlyValid(today)))
        {
            blocks.Add(ClientBlockCodes.NoActiveToRecipient);
        }

        return new ClientOperationalReadiness(blocks.Count == 0, blocks);
    }
}
