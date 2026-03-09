package com.war3.viewer.app.settings;

import com.war3.viewer.app.datasource.GameDataSource;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;

public final class SettingsDialog extends Dialog<Boolean> {

    public SettingsDialog(final Window owner) {
        setTitle("Settings");
        initOwner(owner);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/app.css").toExternalForm());
        getDialogPane().getStyleClass().add("settings-dialog");

        final AppSettings settings = AppSettings.get();
        final String originalTheme = settings.getTheme();

        // ── Tab 1: Appearance ─────────────────────────────────────────────

        // Theme picker
        final Label themeTitle = new Label("Application Theme");
        themeTitle.getStyleClass().add("settings-section-title");

        final String[] themeIds    = new String[AppSettings.THEMES.length];
        final String[] themeLabels = new String[AppSettings.THEMES.length];
        int currentThemeIdx = 0;
        for (int i = 0; i < AppSettings.THEMES.length; i++) {
            themeIds[i]    = AppSettings.THEMES[i][0];
            themeLabels[i] = AppSettings.THEMES[i][1];
            if (themeIds[i].equals(originalTheme)) currentThemeIdx = i;
        }

        final ComboBox<Integer> themeCombo = new ComboBox<>(
                FXCollections.observableArrayList(
                        java.util.stream.IntStream.range(0, themeIds.length)
                                .boxed().toList()));
        themeCombo.setPrefWidth(340);
        themeCombo.getSelectionModel().select(currentThemeIdx);

        final java.util.function.Supplier<ListCell<Integer>> cellFactory = () -> new ListCell<>() {
            @Override
            protected void updateItem(final Integer idx, final boolean empty) {
                super.updateItem(idx, empty);
                if (empty || idx == null) { setGraphic(null); setText(null); return; }
                final String variant = AppSettings.THEMES[idx][2];
                final boolean dark    = "dark".equals(variant);
                final boolean neutral = "neutral".equals(variant);
                final Color dotColor = dark    ? Color.web("#3b4252")
                                     : neutral ? Color.web("#a0a0a0")
                                               : Color.web("#e5e9f0");
                final Circle dot = new Circle(5, dotColor);
                dot.setStroke(Color.web("#88c0d0"));
                dot.setStrokeWidth(1.5);
                final Label name  = new Label(themeLabels[idx]);
                final Label badge = new Label(dark ? "dark" : neutral ? "classic" : "light");
                badge.setStyle(
                        "-fx-font-size: 10px; -fx-padding: 1 5 1 5;" +
                        "-fx-background-radius: 6;" +
                        (dark    ? "-fx-background-color: #3b4252; -fx-text-fill: #88c0d0;"
                         : neutral ? "-fx-background-color: #c8c8c8; -fx-text-fill: #444;"
                                   : "-fx-background-color: #dce5f0; -fx-text-fill: #4c566a;"));
                // Separator hint for non-AtlantaFX themes
                final boolean isBuiltIn = "Modena".equals(themeIds[idx]) || "Caspian".equals(themeIds[idx]);
                final Label origin = new Label(isBuiltIn ? "JavaFX built-in" : "AtlantaFX");
                origin.setStyle("-fx-font-size: 9px; -fx-text-fill: #888; -fx-padding: 0 0 0 4;");
                final HBox row = new HBox(8, dot, name, badge, origin);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        };
        themeCombo.setCellFactory(__ -> cellFactory.get());
        themeCombo.setButtonCell(cellFactory.get());

        themeCombo.setOnAction(e -> {
            final int idx = themeCombo.getValue() == null ? 0 : themeCombo.getValue();
            AppSettings.applyTheme(themeIds[idx]);
        });

        final Label themeNote = new Label(
                "Note: JavaFX built-in themes (Modena, Caspian) do not support custom panel colours.");
        themeNote.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");
        themeNote.setWrapText(true);

        // 3D viewer background colour
        final Label viewerTitle = new Label("3D Viewer");
        viewerTitle.getStyleClass().add("settings-section-title");

        final ColorPicker bgColorPicker = new ColorPicker(safeColor(settings.getViewerBgColor()));
        bgColorPicker.setPrefWidth(160);

        final GridPane viewerGrid = new GridPane();
        viewerGrid.setHgap(10);
        viewerGrid.setVgap(8);
        viewerGrid.add(new Label("Background Color:"), 0, 0);
        viewerGrid.add(bgColorPicker, 1, 0);

        final VBox appearanceContent = new VBox(12,
                themeTitle, themeCombo, themeNote,
                spacer(8),
                viewerTitle, viewerGrid);
        appearanceContent.setPadding(new Insets(14));

        // ── Tab 2: Data Sources ───────────────────────────────────────────

        final Label cascTitle = new Label("Warcraft III Game Path (CASC textures)");
        cascTitle.getStyleClass().add("settings-section-title");

        final TextField gamePathField = new TextField(settings.getGamePath());
        gamePathField.setPromptText("e.g. C:\\Program Files\\Warcraft III");
        HBox.setHgrow(gamePathField, Priority.ALWAYS);

        final Button browseGame = new Button("Browse…");
        browseGame.setOnAction(e -> {
            final DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select Warcraft III Installation Directory");
            final String cur = gamePathField.getText();
            if (cur != null && !cur.isEmpty()) {
                final File dir = new File(cur);
                if (dir.isDirectory()) dc.setInitialDirectory(dir);
            }
            final File chosen = dc.showDialog(getOwner());
            if (chosen != null) gamePathField.setText(chosen.getAbsolutePath());
        });

        final HBox cascRow = new HBox(8, gamePathField, browseGame);
        cascRow.setAlignment(Pos.CENTER_LEFT);

        final Label mpqTitle = new Label("MPQ Archives (legacy, optional)");
        mpqTitle.getStyleClass().add("settings-section-title");

        final ListView<String> mpqList = new ListView<>(
                FXCollections.observableArrayList(settings.getMpqPaths()));
        mpqList.setPrefHeight(110);
        VBox.setVgrow(mpqList, Priority.ALWAYS);

        final Button addMpq = new Button("Add…");
        addMpq.setOnAction(e -> {
            final FileChooser fc = new FileChooser();
            fc.setTitle("Select MPQ Archive");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("MPQ Archive", "*.mpq", "*.MPQ", "*.w3x", "*.w3m"));
            final File chosen = fc.showOpenDialog(getOwner());
            if (chosen != null) mpqList.getItems().add(chosen.getAbsolutePath());
        });
        final Button removeMpq = new Button("Remove");
        removeMpq.setOnAction(e -> {
            final int sel = mpqList.getSelectionModel().getSelectedIndex();
            if (sel >= 0) mpqList.getItems().remove(sel);
        });
        final HBox mpqButtons = new HBox(8, addMpq, removeMpq);

        final VBox dataSourceContent = new VBox(12,
                cascTitle, cascRow,
                spacer(4),
                mpqTitle, mpqList, mpqButtons);
        dataSourceContent.setPadding(new Insets(14));

        // ── Tab 3: Viewer / Camera ────────────────────────────────────────

        final Label cameraTitle = new Label("Thumbnail Preview Camera");
        cameraTitle.getStyleClass().add("settings-section-title");

        final Slider azimuthSlider = new Slider(-180, 180, settings.getPreviewAzimuth());
        azimuthSlider.setShowTickLabels(true);
        azimuthSlider.setShowTickMarks(true);
        azimuthSlider.setMajorTickUnit(45);
        azimuthSlider.setPrefWidth(300);

        final Label azimuthValue = new Label(String.format("%.0f°", settings.getPreviewAzimuth()));
        azimuthSlider.valueProperty().addListener((obs, o, n) ->
                azimuthValue.setText(String.format("%.0f°", n.doubleValue())));

        final Slider elevationSlider = new Slider(-90, 90, settings.getPreviewElevation());
        elevationSlider.setShowTickLabels(true);
        elevationSlider.setShowTickMarks(true);
        elevationSlider.setMajorTickUnit(30);
        elevationSlider.setPrefWidth(300);

        final Label elevationValue = new Label(String.format("%.0f°", settings.getPreviewElevation()));
        elevationSlider.valueProperty().addListener((obs, o, n) ->
                elevationValue.setText(String.format("%.0f°", n.doubleValue())));

        final GridPane cameraGrid = new GridPane();
        cameraGrid.setHgap(10);
        cameraGrid.setVgap(8);
        cameraGrid.add(new Label("Azimuth:"),   0, 0);
        cameraGrid.add(azimuthSlider,           1, 0);
        cameraGrid.add(azimuthValue,            2, 0);
        cameraGrid.add(new Label("Elevation:"), 0, 1);
        cameraGrid.add(elevationSlider,         1, 1);
        cameraGrid.add(elevationValue,          2, 1);

        final VBox viewerContent = new VBox(12, cameraTitle, cameraGrid);
        viewerContent.setPadding(new Insets(14));

        // ── TabPane ───────────────────────────────────────────────────────

        final Tab appearanceTab   = new Tab("Appearance",   appearanceContent);
        final Tab dataSourcesTab  = new Tab("Data Sources", dataSourceContent);
        final Tab viewerTab       = new Tab("Viewer",       viewerContent);
        appearanceTab.setClosable(false);
        dataSourcesTab.setClosable(false);
        viewerTab.setClosable(false);

        final TabPane tabPane = new TabPane(appearanceTab, dataSourcesTab, viewerTab);
        tabPane.setPrefWidth(520);
        tabPane.setPrefHeight(380);

        getDialogPane().setContent(tabPane);

        // ── Result converter ──────────────────────────────────────────────

        setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                final int idx = themeCombo.getValue() == null ? 0 : themeCombo.getValue();
                settings.setTheme(themeIds[idx]);
                settings.setGamePath(gamePathField.getText().trim());
                settings.getMpqPaths().clear();
                settings.getMpqPaths().addAll(new ArrayList<>(mpqList.getItems()));
                settings.setPreviewAzimuth(azimuthSlider.getValue());
                settings.setPreviewElevation(elevationSlider.getValue());
                settings.setViewerBgColor(toHex(bgColorPicker.getValue()));
                AppSettings.save();
                GameDataSource.refresh();
                return Boolean.TRUE;
            }
            AppSettings.applyTheme(originalTheme);
            return Boolean.FALSE;
        });
    }

    private static Label spacer(final double height) {
        final Label l = new Label();
        l.setMinHeight(height);
        return l;
    }

    private static Color safeColor(final String hex) {
        try { return Color.web(hex); } catch (final Exception e) { return Color.web("#1a1e26"); }
    }

    private static String toHex(final Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed()   * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue()  * 255));
    }
}
