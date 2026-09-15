using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Documents;

public static class DocumentPresentation
{
    public static string ToPortugueseLabel(RecognizedDocumentType type) => type switch
    {
        RecognizedDocumentType.Unclassified => "Tipo não identificado",
        RecognizedDocumentType.Vacation => "Férias",
        RecognizedDocumentType.FgtsDigital => "FGTS Digital",
        RecognizedDocumentType.Payroll => "Folha de pagamento",
        RecognizedDocumentType.FederalRevenueCollection => "Guia de arrecadação",
        RecognizedDocumentType.ThirteenthSalary => "13º salário",
        RecognizedDocumentType.ProLabore => "Pró-labore",
        RecognizedDocumentType.Termination => "Rescisão",
        _ => "Documento contábil",
    };
}
