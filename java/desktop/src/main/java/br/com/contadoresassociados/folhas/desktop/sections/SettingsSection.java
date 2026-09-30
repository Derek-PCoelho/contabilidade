package br.com.contadoresassociados.folhas.desktop.sections;

import static br.com.contadoresassociados.folhas.desktop.ui.Controls.*;
import static br.com.contadoresassociados.folhas.desktop.ui.Ui.*;

import br.com.contadoresassociados.folhas.desktop.state.RolloutModel;
import br.com.contadoresassociados.folhas.desktop.state.SettingsModel;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

/** Configurações — mesma ordem e textos de {@code MainWindow.axaml}. */
public final class SettingsSection implements Section {

    private final SettingsModel model;
    private final br.com.contadoresassociados.folhas.desktop.state.AccountModel account;
    private final RolloutModel rollout;
    private final Node root;

    public SettingsSection(SettingsModel model, br.com.contadoresassociados.folhas.desktop.state.AccountModel account,
            RolloutModel rollout) {
        this.model = model;
        this.account = account;
        this.rollout = rollout;
        var content = new VBox(14, account(), email(), backup(), folders(), serverHelp(), updates(), retention(), about(), support());
        content.setMaxWidth(1040);
        var centered = new HBox(content);
        centered.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(content, Priority.ALWAYS);
        root = scroll(centered, 28);
        root.setAccessibleText("Configurações");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShown() {
        model.open();
        account.refresh();
        rollout.refresh();
    }

    // ------------------------------------------------------------------ conta do escritório (6.1)

    private Node account() {
        var signIn = button("Entrar com a conta do escritório", "primary", account::signIn);
        signIn.disableProperty().bind(account.canSignIn().not());
        var cancel = visibleWhen(button("Cancelar login", "ghost", account::cancelSignIn), account.signingIn);
        var renew = visibleWhen(button("Renovar sessão", "ghost", account::renew), account.signedIn());
        var signOut = visibleWhen(button("Sair da conta", "secondary", account::signOut), account.signedIn());
        var actions = new javafx.scene.layout.FlowPane(8, 8, hiddenWhen(signIn, account.signedIn()), cancel, renew, signOut);
        var server = muted("Server do escritório: " + account.serverAddress());
        var connected = new VBox(10, server, actions,
                muted("O login abre a página segura do escritório no navegador. A senha nunca é digitada neste aplicativo; "
                        + "a sessão fica no cofre protegido do sistema e pode ser encerrada aqui ou pelo administrador."));
        var card = card(10, eyebrow("CONTA DO ESCRITÓRIO"), h2("Login no Server central"),
                wrap(colored(text(account.status), "#514C42", true)), muted(account.details));
        if (account.connectedProfile()) {
            card.getChildren().add(connected);
        }
        card.setAccessibleText("Conta do escritório");
        return card;
    }

    // ------------------------------------------------------------------ e-mail

    private Node email() {
        var why = new VBox(4, colored(new Label("Por que os botões ainda estão bloqueados?"), "#6C4D11", true),
                wrap(colored(new Label("É necessário registrar oficialmente este aplicativo no Google e/ou na Microsoft e configurar o identificador público de cada provedor. Isso não é sua senha e não deve ser improvisado dentro do aplicativo."), "#5E4A1E", false)),
                wrap(colored(new Label("Enquanto isso, o modo Teste seguro permite conferir o fluxo inteiro sem enviar nada."), "#5E4A1E", false)));
        boxed(why, "#FFF5E4", "#E5C36B", 9, "11");
        var keep = checkBox("Manter esta conta conectada neste computador", model.keepEmailSession);
        var disconnect = button("Desconectar conta atual", "ghost", model::disconnectEmail);
        disconnect.disableProperty().bind(model.canDisconnect().not());
        return card(10, eyebrow("CONTAS DE E-MAIL"), h2("Conecte com segurança"),
                wrap(colored(text(model.emailStatus), "#514C42", false)), muted(model.emailHelp), why,
                provider("G", "#4285F4", "#FFFFFF", 17, "Google Gmail", "Conectar com Google", model.canConnectGmail(),
                        "O botão será liberado após configurar o registro oficial do Google."),
                provider("O", "#FFFFFF", "#1473E6", 7, "Outlook / Microsoft 365", "Conectar com Microsoft",
                        model.canConnectMicrosoft(), "O botão será liberado após configurar o registro oficial da Microsoft."),
                keep, muted("A autorização fica no cofre protegido do sistema e nunca é gravada no projeto. Desmarque para desconectar ao fechar o aplicativo."),
                disconnect);
    }

    private Node provider(String letter, String fg, String bg, double radius, String name, String action, BooleanBinding enabled,
            String hint) {
        var mark = new StackPane(colored(new Label(letter), fg, true));
        mark.setMinSize(34, 34);
        mark.setMaxSize(34, 34);
        mark.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: " + radius + ";");
        var head = new HBox(9, mark, new VBox(text(name, "strong"), muted("Suportado pelo aplicativo")));
        head.setAlignment(Pos.CENTER_LEFT);
        var connect = stretch(button(action, "primary", model::connectEmail));
        connect.disableProperty().bind(enabled.not());
        var small = muted(hint);
        small.setStyle("-fx-font-size: 10px;");
        var box = soft(7, head, connect, small);
        box.setStyle(box.getStyle() + " -fx-border-color: #E2DDD3; -fx-border-radius: 10;");
        return box;
    }

    // ------------------------------------------------------------------ cópia de segurança

    private Node backup() {
        var password = new PasswordField();
        password.textProperty().bindBidirectional(model.backupPassword);
        password.setPromptText("Digite somente para salvar ou abrir");
        password.setAccessibleText("Senha da cópia");
        var buttons = columns(8, 0, 1, 1);
        buttons.add(stretch(button("Salvar cópia", "primary", this::saveBackup)), 0, 0);
        buttons.add(stretch(button("Escolher cópia", "ghost", this::chooseBackup)), 1, 0);
        var confirm = button("Confirmar restauração", "secondary", model::confirmRestore);
        confirm.disableProperty().bind(model.canApplyRestore.not());
        return card(10, eyebrow("CÓPIA DE SEGURANÇA"), h2("Cadastros e modelos de mensagem"),
                muted("Inclui: clientes, contatos e modelos de mensagem. Não inclui: PDFs, senhas, tokens, histórico de envios nem o acervo de documentos."),
                muted("Como restaurar: escolha a cópia, confira o resumo e só então confirme a restauração."),
                fieldLabel("Senha da cópia (mínimo de 12 caracteres)"), password,
                muted("A senha não é salva nem pode ser recuperada. Guarde-a separadamente do arquivo."), buttons,
                soft(0, wrap(colored(text(model.restoreSummary), "#5E574C", false))), confirm);
    }

    private FileChooser backupChooser(String title) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Cópia protegida", "*.fmbackup", "*.json", "*.*"));
        return chooser;
    }

    private void saveBackup() {
        var chooser = backupChooser("Salvar cópia de segurança");
        chooser.setInitialFileName("folhas-da-michelly-cadastros.fmbackup");
        var file = chooser.showSaveDialog(root.getScene().getWindow());
        if (file != null) {
            model.saveBackup(file.toPath());
        }
    }

    private void chooseBackup() {
        var file = backupChooser("Escolher cópia de segurança").showOpenDialog(root.getScene().getWindow());
        if (file != null) {
            model.previewRestore(file.toPath());
        }
    }

    // ------------------------------------------------------------------ pastas

    private Node folders() {
        return card(12, new VBox(3, eyebrow("PASTAS DE TRABALHO"), h2("Entrada, acervo e relatórios no lugar certo"),
                        muted("As pastas e o período selecionado ficam salvos somente neste computador. Cada PDF importado é copiado para o acervo em ano/mês, e conteúdo repetido continua bloqueado pela conferência.")),
                folderRow("Pasta de entrada dos PDFs", model.inputFolderDisplay(), "Escolher pasta de entrada", model::chooseInputFolder),
                divider(),
                folderRow("Acervo organizado dos documentos", model.archiveDisplay(), "Escolher pasta do acervo",
                        model::chooseArchiveFolder),
                divider(),
                folderRow("Pasta dos relatórios", model.reportOutputDirectory, "Escolher pasta dos relatórios",
                        model::chooseReportFolder),
                checkBox("Ler também as subpastas da pasta de entrada", model.includeSubfolders));
    }

    private Node folderRow(String label, ObservableValue<String> value, String action, Consumer<Path> onChosen) {
        var left = new VBox(fieldLabel(label), wrap(colored(text(value), "#5E574C", false)));
        var row = new HBox(10, grow(left), button(action, "ghost", () -> {
            var chooser = new DirectoryChooser();
            chooser.setTitle(action);
            var current = value.getValue() == null ? null : new File(value.getValue());
            if (current != null && current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            var folder = chooser.showDialog(root.getScene().getWindow());
            if (folder != null) {
                onChosen.accept(folder.toPath());
            }
        }));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node serverHelp() {
        return expander("Quando Server, PostgreSQL e login são necessários?", new VBox(8,
                colored(new Label("Neste computador"), "#2F6D3B", true),
                muted("Clientes, modelos, documentos, conferência, simulação e rascunhos locais funcionam sem Server e sem PostgreSQL."),
                colored(new Label("Perfil compartilhado"), "#8C691B", true),
                muted("Server é o serviço central que atende vários computadores; PostgreSQL é o banco usado por esse serviço. Eles só são necessários para dados compartilhados, controle central e integrações reais."),
                colored(new Label("Token de acesso"), "#8C691B", true),
                muted("É uma autorização temporária criada pelo botão “Entrar com a conta do escritório” — nunca algo que o cliente precise inventar ou digitar. O sistema guarda a sessão no cofre protegido do macOS ou Windows e a renova sozinho enquanto for válida.")), false);
    }

    // ------------------------------------------------------------------ atualizações

    private Node updates() {
        var progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(7);
        progress.progressProperty().bind(Bindings.createDoubleBinding(() -> model.updateProgress.get() / 100d, model.updateProgress));
        progress.setAccessibleText("Progresso da atualização");
        var version = new Label("Versão " + model.currentVersion());
        version.setStyle("-fx-font-size: 18px; -fx-font-weight: 600;");
        var check = stretch(button("Verificar atualização", "secondary", model::checkForUpdates));
        check.disableProperty().bind(model.updating);
        var download = stretch(button("Baixar", "primary", model::downloadUpdate));
        download.disableProperty().bind(model.canDownloadUpdate.not());
        var apply = stretch(button("Aplicar e reiniciar", "ghost", model::applyUpdate));
        apply.disableProperty().bind(model.canApplyUpdate.not());
        var pair = columns(8, 0, 1, 1);
        pair.add(download, 0, 0);
        pair.add(apply, 1, 0);
        var actions = new VBox(8, fieldLabel("Versão instalada"), version, muted("Canal estável"), check, pair);
        actions.setMaxWidth(420);
        var summary = soft(5, wrap(colored(text(model.updateSummary), "#514C42", false)), visibleWhen(progress, model.updating));
        var card = card(14, new VBox(8, eyebrow("ATUALIZAÇÕES"), h2("Versões conferidas, no seu ritmo"),
                muted("O aplicativo consulta somente o canal estável. Nada é instalado sem download concluído e sua decisão de reiniciar."),
                summary, muted("Versões de teste ficam restritas aos detalhes técnicos de suporte e não são oferecidas nesta tela.")), actions);
        card.setAccessibleText("Atualizações do aplicativo");
        return card;
    }

    // ------------------------------------------------------------------ retenção

    private Node retention() {
        var controls = new VBox(8, fieldLabel("Quando lembrar de revisar"), comboBox(model.retentionOptions, model.retention),
                button("Analisar o acervo agora", "secondary", model::reviewRetention));
        controls.setMaxWidth(360);
        return card(14, new VBox(7, eyebrow("ORGANIZAÇÃO E RETENÇÃO"), h2("Revisar sem excluir automaticamente"),
                muted("O prazo localiza documentos antigos; ele nunca apaga arquivos. A decisão depende das obrigações fiscais, contratuais e da política do escritório."),
                soft(0, wrap(colored(text(model.retentionSummary), "#5E574C", false)))), controls);
    }

    // ------------------------------------------------------------------ sobre e suporte

    private Node about() {
        var brand = image("branding/aaa2.png", 120, 92, "#24221E");
        var team = image("branding/aaa1.png", 180, 96, "#EEE9DF");
        var text = new VBox(6, eyebrow("SOBRE O APLICATIVO"), h2("Tecnologia a serviço do cuidado contábil"),
                muted("Folhas da Michelly organiza documentos, reduz tarefas repetitivas e mantém a decisão final com o profissional responsável."));
        text.setAlignment(Pos.CENTER_LEFT);
        var row = new HBox(18, brand, grow(text), team);
        row.setAlignment(Pos.CENTER_LEFT);
        return card(0, row);
    }

    private Node image(String resource, double width, double height, String background) {
        var box = new StackPane();
        box.setMinSize(width, height);
        box.setMaxSize(width, height);
        box.setStyle("-fx-background-color: " + background + "; -fx-background-radius: 10;");
        var stream = SettingsSection.class.getResourceAsStream("/br/com/contadoresassociados/folhas/desktop/" + resource);
        if (stream != null) {
            var view = new ImageView(new Image(stream, width, height, true, true));
            box.getChildren().add(view);
        }
        return box;
    }

    /** Produção gradual — somente leitura; oculto enquanto a etapa estiver fechada. */
    private Node productionPanel() {
        var gates = new VBox(6);
        rollout.productionGates().forEach(g -> gates.getChildren().add(new Label(g)));
        var blockers = new VBox(4);
        rollout.productionBlockers().forEach(b -> blockers.getChildren().add(wrap(muted("• " + b))));
        var content = new VBox(10,
                colored(new Label(rollout.productionStageLabel()), "#8C691B", true),
                bold(new Label(rollout.productionStatusTitle())),
                wrap(muted(rollout.productionStatusSummary())),
                soft(6, gates),
                blockers,
                wrap(bold(new Label(rollout.productionLimitsSummary()))),
                wrap(muted(rollout.productionRolesSummary())),
                wrap(colored(new Label("Painel informativo: nenhuma trava de produção pode ser aberta por esta tela."),
                        "#2D6535", false)));
        blockers.setVisible(!rollout.productionReady() && rollout.productionBlockers().size() > 1);
        blockers.setManaged(blockers.isVisible());
        var pane = expander("Produção gradual (uso exclusivo do suporte)", content, false);
        pane.setAccessibleText("Prontidão da produção gradual");
        pane.setVisible(rollout.productionVisible());
        pane.setManaged(rollout.productionVisible());
        return pane;
    }

    /** Piloto supervisionado — checklist auditado; visível só com o piloto habilitado. */
    private Node pilotPanel() {
        var percent = colored(text(rollout.checklistProgress.asString("%d%%")), "#8C691B", true);
        var header = new HBox(bold(new Label("Checklist de homologação")), spacer(), percent);
        var progress = new ProgressBar();
        progress.progressProperty().bind(rollout.checklistProgress.divide(100.0));
        progress.setAccessibleText("Progresso do checklist de homologação");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(7);
        var checks = new VBox(8);
        RolloutModel.CHECKLIST_LABELS.forEach((key, label) -> checks.getChildren().add(checkBox(label, rollout.checklist.get(key))));
        var safeguard = boxed(new VBox(wrap(colored(new Label(rollout.pilotSafeguardSummary()), "#2D6535", true))),
                "#EAF4E8", null, 9, "12");
        var save = button("Salvar checklist de homologação", "primary", rollout::saveChecklist);
        save.disableProperty().bind(rollout.saving);
        var content = new VBox(10,
                colored(new Label(rollout.pilotEnvironmentLabel()), "#8C691B", true),
                bold(text(rollout.pilotStatusTitle)),
                wrap(muted(rollout.pilotStatusSummary)),
                soft(6, header, progress, wrap(muted(rollout.pilotMetricsSummary))),
                checks, safeguard, save);
        var pane = expander("Piloto supervisionado (uso exclusivo do suporte)", content, false);
        pane.setAccessibleText("Preparação do piloto supervisionado");
        pane.setVisible(rollout.pilotMode());
        pane.setManaged(rollout.pilotMode());
        return pane;
    }

    private static Label bold(Label label) {
        label.setStyle("-fx-font-weight: 600;");
        return label;
    }

    private Node support() {
        // Grid "220,*" da versão .NET: rótulos em coluna fixa, valores no restante.
        var grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(7);
        var labels = new javafx.scene.layout.ColumnConstraints(220);
        var values = new javafx.scene.layout.ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, values);
        grid.add(fieldLabel("Marco de desenvolvimento"), 0, 0);
        grid.add(colored(new Label(SettingsModel.PHASE), "#514C42", false), 1, 0);
        grid.add(fieldLabel("Ambiente"), 0, 1);
        grid.add(colored(new Label(SettingsModel.ENVIRONMENT), "#514C42", false), 1, 1);
        grid.add(fieldLabel("Integrações"), 0, 2);
        grid.add(wrap(colored(text(model.technicalDetails), "#514C42", false)), 1, 2);
        var scenario = comboBox(model.scenarios(), model.scenario());
        scenario.setMaxWidth(260);
        var destination = textField(model.testDestination(), "Caixa de teste");
        destination.setMaxWidth(320);
        return expander("Detalhes técnicos para suporte", new VBox(10,
                muted("Esta área é destinada ao proprietário técnico ou ao suporte. Ela não interfere no trabalho cotidiano."),
                muted("Produção gradual e piloto supervisionado são controles de homologação e liberação técnica; não fazem parte do trabalho diário do cliente e permanecem ocultos quando fechados."),
                grid, productionPanel(), pilotPanel(), divider(), fieldLabel("Simulação de falhas para suporte"),
                muted("Não altere durante o uso normal. Esta opção existe somente para testar recuperação e mensagens de erro sem enviar e-mail real."),
                scenario, destination), false);
    }
}
