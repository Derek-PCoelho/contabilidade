package br.com.contadoresassociados.folhas.infrastructure.reports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** CSV com BOM UTF-8, separador ';' (Excel pt-BR), todos os campos entre aspas e sanitizados (7.8). */
final class CsvWriter {

    private CsvWriter() {
    }

    static Path write(Path directory, String fileName, List<String[]> rows) throws IOException {
        var path = directory.resolve(fileName);
        var sb = new StringBuilder("\uFEFF");
        for (var row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append('"').append(ReportText.sanitize(row[i]).replace("\"", "\"\"")).append('"');
            }
            sb.append("\r\n");
        }
        Files.writeString(path, sb, StandardCharsets.UTF_8);
        return path;
    }
}
