package com.war3.viewer.app;

import com.hiveworkshop.rms.parsers.mdlx.MdlxModel;
import com.war3.viewer.app.viewer.ModelDetailDialog;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ModelCard extends VBox {
    private static final double PREVIEW_WIDTH = 230;
    private static final double PREVIEW_HEIGHT = 148;

    private final StackPane previewPane = new StackPane();

    public ModelCard(
            final Path mdxFile,
            final Path rootDirectory,
            final MdxPreviewFactory previewFactory,
            final Executor executor
    ) {
        getStyleClass().add("asset-card");
        setSpacing(8);
        setPadding(new Insets(10));
        setPrefWidth(252);
        setMaxWidth(252);

        previewPane.getStyleClass().add("preview-pane");
        previewPane.setPrefSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        previewPane.setMinSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        previewPane.setMaxSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        VBox.setVgrow(previewPane, Priority.NEVER);

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(24, 24);
        previewPane.getChildren().setAll(indicator);

        Label nameLabel = new Label(mdxFile.getFileName().toString());
        nameLabel.getStyleClass().add("asset-name");
        nameLabel.setWrapText(true);

        Label pathLabel = new Label(toRelativePath(rootDirectory, mdxFile));
        pathLabel.getStyleClass().add("asset-path");
        pathLabel.setWrapText(true);

        Tooltip.install(this, new Tooltip(mdxFile.toString()));

        getChildren().addAll(previewPane, nameLabel, pathLabel);
        setAlignment(Pos.TOP_LEFT);

        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                final ModelDetailDialog dialog = new ModelDetailDialog(
                        getScene().getWindow(), mdxFile, rootDirectory, previewFactory);
                dialog.show();
            }
        });

        loadPreviewAsync(mdxFile, rootDirectory, previewFactory, executor);
    }

    private void loadPreviewAsync(
            final Path mdxFile,
            final Path rootDirectory,
            final MdxPreviewFactory previewFactory,
            final Executor executor
    ) {
        CompletableFuture
                .supplyAsync(() -> parseModel(previewFactory, mdxFile), executor)
                .thenAccept(model -> Platform.runLater(() -> renderModel(model, mdxFile, rootDirectory, previewFactory)))
                .exceptionally(error -> {
                    Platform.runLater(() -> showError("Preview failed"));
                    return null;
                });
    }

    private MdlxModel parseModel(final MdxPreviewFactory previewFactory, final Path mdxFile) {
        try {
            return previewFactory.loadModel(mdxFile);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void renderModel(
            final MdlxModel model,
            final Path mdxFile,
            final Path rootDirectory,
            final MdxPreviewFactory previewFactory
    ) {
        if (model == null) {
            showError("Cannot load file");
            return;
        }

        try {
            SubScene subScene = previewFactory.buildSubScene(model, mdxFile, rootDirectory, PREVIEW_WIDTH, PREVIEW_HEIGHT);
            previewPane.getChildren().setAll(subScene);
        } catch (Exception ignored) {
            showError("Preview unavailable");
        }
    }

    private void showError(final String message) {
        Label error = new Label(message);
        error.getStyleClass().add("preview-error");
        previewPane.getChildren().setAll(error);
    }

    private String toRelativePath(final Path root, final Path file) {
        if (root == null) {
            return file.toString();
        }
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException ignored) {
            return file.toString();
        }
    }
}
