package com.war3.viewer.app.settings;

import com.war3.viewer.app.datasource.GameDataSource;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
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

        // ----- Layout -----
        final VBox content = new VBox(14,
                cascLabel, cascRow,
                new Separator(),
                mpqLabel, mpqList, mpqButtons,
                new Separator(),
                cameraLabel, cameraGrid
        );
        content.setPadding(new Insets(16));
        content.setPrefWidth(560);

        getDialogPane().setContent(content);

        // ----- Result converter -----
        setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                settings.setGamePath(gamePathField.getText().trim());
                settings.getMpqPaths().clear();
                settings.getMpqPaths().addAll(new ArrayList<>(mpqList.getItems()));
                settings.setPreviewAzimuth(azimuthSlider.getValue());
                settings.setPreviewElevation(elevationSlider.getValue());
                AppSettings.save();
                GameDataSource.refresh();
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
    }
}
