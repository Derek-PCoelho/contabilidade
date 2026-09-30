package br.com.contadoresassociados.folhas.desktop.state;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Estado compartilhado da janela (seção atual, competência, mensagem de status e contadores da
 * visão geral). Equivale às propriedades de navegação do {@code MainViewModel} da versão .NET.
 */
public final class ShellState {

    private final ObjectProperty<AppSection> section = new SimpleObjectProperty<>(AppSection.HOME);
    private final ObservableList<OperationalPeriod.YearOption> years;
    private final ObservableList<OperationalPeriod.MonthOption> months =
            FXCollections.observableArrayList(OperationalPeriod.months());
    private final ObjectProperty<OperationalPeriod.YearOption> selectedYear = new SimpleObjectProperty<>();
    private final ObjectProperty<OperationalPeriod.MonthOption> selectedMonth = new SimpleObjectProperty<>();
    private final StringProperty statusMessage = new SimpleStringProperty(
            "Pronto. Comece importando documentos ou cadastrando um cliente.");
    private final IntegerProperty visibleDocumentCount = new SimpleIntegerProperty();
    private final IntegerProperty blockedDocumentCount = new SimpleIntegerProperty();
    private final IntegerProperty approvedGroupCount = new SimpleIntegerProperty();
    private final StringProperty emailSetupNoticeTitle = new SimpleStringProperty("Conecte uma conta de e-mail");
    private final StringProperty emailSetupNoticeText = new SimpleStringProperty(
            "Os envios reais só ficam disponíveis depois de conectar e testar uma conta. Até lá, use o modo de teste seguro.");
    private final BooleanProperty showEmailSetupNotice = new SimpleBooleanProperty(true);
    private final StringBinding workPeriodLabel;

    public ShellState(int currentYear, int currentMonth) {
        years = FXCollections.observableArrayList(OperationalPeriod.years(currentYear));
        selectedYear.set(years.stream().filter(y -> Integer.valueOf(currentYear).equals(y.year())).findFirst()
                .orElse(OperationalPeriod.YearOption.ALL));
        selectedMonth.set(months.stream().filter(m -> Integer.valueOf(currentMonth).equals(m.month())).findFirst()
                .orElse(months.getFirst()));
        workPeriodLabel = Bindings.createStringBinding(
                () -> OperationalPeriod.label(
                        selectedYear.get() == null ? null : selectedYear.get().year(),
                        selectedMonth.get() == null ? null : selectedMonth.get().month()),
                selectedYear, selectedMonth);
    }

    public ObjectProperty<AppSection> section() {
        return section;
    }

    public BooleanBinding is(AppSection value) {
        return Bindings.equal(section, value);
    }

    public void show(AppSection value) {
        section.set(value);
    }

    public StringBinding sectionTitle() {
        return Bindings.createStringBinding(() -> section.get().title(), section);
    }

    public StringBinding sectionDescription() {
        return Bindings.createStringBinding(() -> section.get().description(), section);
    }

    public ObservableList<OperationalPeriod.YearOption> years() {
        return years;
    }

    public ObservableList<OperationalPeriod.MonthOption> months() {
        return months;
    }

    public ObjectProperty<OperationalPeriod.YearOption> selectedYear() {
        return selectedYear;
    }

    public ObjectProperty<OperationalPeriod.MonthOption> selectedMonth() {
        return selectedMonth;
    }

    public StringBinding workPeriodLabel() {
        return workPeriodLabel;
    }

    public StringProperty statusMessage() {
        return statusMessage;
    }

    public void status(String message) {
        statusMessage.set(message);
    }

    public IntegerProperty visibleDocumentCount() {
        return visibleDocumentCount;
    }

    public IntegerProperty blockedDocumentCount() {
        return blockedDocumentCount;
    }

    public IntegerProperty approvedGroupCount() {
        return approvedGroupCount;
    }

    public StringProperty emailSetupNoticeTitle() {
        return emailSetupNoticeTitle;
    }

    public StringProperty emailSetupNoticeText() {
        return emailSetupNoticeText;
    }

    public BooleanProperty showEmailSetupNotice() {
        return showEmailSetupNotice;
    }
}
