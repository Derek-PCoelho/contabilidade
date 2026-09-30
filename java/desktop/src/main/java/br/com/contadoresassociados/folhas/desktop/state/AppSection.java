package br.com.contadoresassociados.folhas.desktop.state;

/** Seções da janela principal com título e descrição idênticos à versão .NET. */
public enum AppSection {
    HOME("Visão geral", "Acompanhe o trabalho e continue de onde parou."),
    CLIENTS("Clientes", "Dados essenciais, contato de entrega e preferências em um único cadastro."),
    DOCUMENTS("Documentos", "Importe, confira as pendências e aprove somente o que estiver correto."),
    DISPATCH("Mensagens e envios", "Prepare a mensagem, confira anexos e conclua cada comunicação."),
    REPORTS("Relatórios", "Gere registros claros para conferência e prestação de contas."),
    HISTORY("Histórico", "Consulte as decisões e alterações realizadas no aplicativo."),
    SETTINGS("Configurações", "Contas de e-mail, cópia de segurança e informações do aplicativo.");

    private final String title;
    private final String description;

    AppSection(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }
}
