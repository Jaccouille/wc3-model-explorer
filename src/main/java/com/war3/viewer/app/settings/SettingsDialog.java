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
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
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

        // Remember the current theme so we can restore it on Cancel
        final String originalTheme = settings.getTheme();

        // ----- Appearance section -----
        final Label appearanceLabel = new Label("Appearance");
        appearanceLabel.getStyleClass().add("settings-section-title");

        // Build ComboBox items from the THEMES catalogue
        final String[] themeIds     = new String[AppSettings.THEMES.length];
        final String[] themeLabels  = new String[AppSettings.THEMES.length];
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
        themeCombo.setPrefWidth(300);
        themeCombo.getSelectionModel().select(currentThemeIdx);

        // Custom cell: coloured dot + name + dark/light badge
        final java.util.function.Supplier<ListCell<Integer>> cellFactory = () -> new ListCell<>() {
            @Override
            protected void updateItem(final Integer idx, final boolean empty) {
                super.updateItem(idx, empty);
                if (empty || idx == null) { setGraphic(null); setText(null); return; }
                final boolean dark = "dark".equals(AppSettings.THEMES[idx][2]);
                final Circle dot = new Circle(5,
                        dark ? Color.web("#3b4252") : Color.web("#e5e9f0"));
                dot.setStroke(Color.web("#88c0d0"));
                dot.setStrokeWidth(1.5);
                final Label name = new Label(themeLabels[idx]);
                final Label badge = new Label(dark ? "dark" : "light");
                badge.setStyle(
                        "-fx-font-size: 10px; -fx-padding: 1 5 1 5;" +
                        "-fx-background-radius: 6;" +
                        (dark ? "-fx-background-color: #3b4252; -fx-text-fill: #88c0d0;"
                              : "-fx-background-color: #dce5f0; -fx-text-fill: #4c566a;"));
                final HBox row = new HBox(8, dot, name, badge);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        };
        themeCombo.setCellFactory(__ -> cellFactory.get());
        themeCombo.setButtonCell(cellFactory.get());

        // Live preview — apply theme immediately when selection changes
        themeCombo.setOnAction(e -> {
            final int idx = themeCombo.getValue() == null ? 0 : themeCombo.getValue();
            AppSettings.applyTheme(themeIds[idx]);
        });

        // ----- CASC section -----
        final Label cascLabel = new Label("Warcraft III Game Path (for CASC textures)");
        cascLabel.getStyleClass().add("settings-section-title");

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

        // ----- MPQ section -----
        final Label mpqLabel = new Label("MPQ Archives (legacy, optional)");
        mpqLabel.getStyleClass().add("settings-section-title");

        final ListView<String> mpqList = new ListView<>(
                FXCollections.observableArrayList(settings.getMpqPaths()));
        mpqList.setPrefHeight(100);

        final Button addMpq = new Button("Add…");
        addMpq.setOnAction(e -> {
            final FileChooser fc = new FileChooser();
            fc.setTitle("Select MPQ Archive");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MPQ Archive", "*.mpq", "*.MPQ", "*.w3x", "*.w3m"));
            final File chosen = fc.showOpenDialog(getOwner());
            if (chosen != null) mpqList.getItems().add(chosen.getAbsolutePath());
        });
        final Button removeMpq = new Button("Remove");
        removeMpq.setOnAction(e -> {
            final int sel = mpqList.getSelectionModel().getSelectedIndex();
            if (sel >= 0) mpqList.getItems().remove(sel);
        });
        final HBox mpqButtons = new HBox(8, addMpq, removeMpq);

        // ----- Camera section -----
        final Label cameraLabel = new Label("Thumbnail Preview Camera");
        cameraLabel.getStyleClass().add("settings-section-title");

        final Slider azimuthSlider = new Slider(-180, 180, settings.getPreviewAzimuth());
        azimuthSlider.setShowTickLabels(true);
        azimuthSlider.setShowTickMarks(true);
        azimuthSlider.setMajorTickUnit(45);
        azimuthSlider.setPrefWidth(320);

        final Label azimuthValue = new Label(String.format("%.0f°", settings.getPreviewAzimuth()));
        azimuthSlider.valueProperty().addListener((obs, o, n) ->
                azimuthValue.setText(String.format("%.0f°", n.doubleValue())));

        final Slider elevationSlider = new Slider(-90, 90, settings.getPreviewElevation());
        elevationSlider.setShowTickLabels(true);
        elevationSlider.setShowTickMarks(true);
        elevationSlider.setMajorTickUnit(30);
        elevationSlider.setPrefWidth(320);

        final Label elevationValue = new Label(String.format("%.0f°", settings.getPreviewElevation()));
        elevationSlider.valueProperty().addListener((obs, o, n) ->
                elevationValue.setText(String.format("%.0f°", n.doubleValue())));

        final GridPane cameraGrid = new GridPane();
        cameraGrid.setHgap(10);
        cameraGrid.setVgap(8);
        cameraGrid.add(new Label("Azimuth:"), 0, 0);
        cameraGrid.add(azimuthSlider, 1, 0);
        cameraGrid.add(azimuthValue, 2, 0);
        cameraGrid.add(new Label("Elevation:"), 0, 1);
        cameraGrid.add(elevationSlider, 1, 1);
        cameraGrid.add(elevationValue, 2, 1);

        // ----- Viewer section -----
        final Label viewerLabel = new Label("3D Viewer");
        viewerLabel.getStyleClass().add("settings-section-title");

        final ColorPicker bgColorPicker = new ColorPicker(safeColor(settings.getViewerBgColor()));
        bgColorPicker.setPrefWidth(140);

        final GridPane viewerGrid = new GridPane();
        viewerGrid.setHgap(10);
        viewerGrid.setVgap(8);
        viewerGrid.add(new Label("Background Color:"), 0, 0);
        viewerGrid.add(bgColorPicker, 1, 0);

        // ----- Layout -----
        final VBox content = new VBox(14,
                appearanceLabel, themeCombo,
                new Separator(),
                cascLabel, cascRow,
                new Separator(),
                mpqLabel, mpqList, mpqButtons,
                new Separator(),
                cameraLabel, cameraGrid,
                new Separator(),
                viewerLabel, viewerGrid
        );
        content.setPadding(new Insets(16));
        content.setPrefWidth(580);

        getDialogPane().setContent(content);

        // ----- Result converter -----
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
            // Cancel — restore the theme that was active before the dialog opened
            AppSettings.applyTheme(originalTheme);
            return Boolean.FALSE;
        });
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
