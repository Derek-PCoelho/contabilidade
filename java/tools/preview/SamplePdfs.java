import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Gera PDFs fictícios para testar a importação na pré-visualização (nenhum dado real). */
public final class SamplePdfs {
    public static void main(String[] args) throws Exception {
        var dir = Files.createDirectories(Path.of(args.length > 0 ? args[0] : "/tmp/folhas-preview/exemplos"));
        write(dir.resolve("folha-padaria-2026-09.pdf"), List.of("FOLHA DE PAGAMENTO", "Empregador: Padaria Sao Joao Ltda",
                "Empregador CNPJ: 11.222.333/0001-81", "Competencia: 09/2026", "Total da folha: R$ 12.345,67"));
        write(dir.resolve("prolabore-padaria-2026-09.pdf"), List.of("PRO-LABORE", "Empresa: Padaria Sao Joao Ltda",
                "CNPJ: 11.222.333/0001-81", "Competencia: 09/2026", "Valor total: R$ 3.000,00"));
        write(dir.resolve("folha-cliente-desconhecido-2026-09.pdf"), List.of("FOLHA DE PAGAMENTO", "Empregador: Mercado Aurora Ltda",
                "Empregador CNPJ: 45.723.174/0001-10", "Competencia: 09/2026", "Total da folha: R$ 8.900,00"));
        Files.writeString(dir.resolve("planilha-nao-suportada.xlsx"), "exemplo");
        System.out.println("Exemplos em " + dir);
    }

    private static void write(Path file, List<String> lines) throws Exception {
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.setLeading(14);
                cs.newLineAtOffset(50, 740);
                for (var line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(file.toFile());
        }
    }
}
