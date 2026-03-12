package com.war3.viewer.app;

import com.hiveworkshop.rms.parsers.mdlx.MdlxModel;
import com.war3.viewer.app.viewer.ModelDetailDialog;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ModelCard extends VBox {
    /** Height/width ratio of the preview pane (fixed aspect). */
    private static final double PREVIEW_ASPECT = 148.0 / 230.0;

    private final StackPane previewPane = new StackPane();
    private Timeline shimmerTimeline;

    public ModelCard(
            final Path mdxFile,
            final Path rootDirectory,
            final MdxPreviewFactory previewFactory,
            final Executor executor,
            final int cardWidth
    ) {
        final double previewW = cardWidth - 22;
        final double previewH = Math.round(previewW * PREVIEW_ASPECT);

        getStyleClass().add("asset-card");
        setSpacing(8);
        setPadding(new Insets(10));
        setPrefWidth(cardWidth);
        setMaxWidth(cardWidth);

        previewPane.getStyleClass().add("preview-pane");
        previewPane.setPrefSize(previewW, previewH);
        previewPane.setMinSize(previewW, previewH);
        previewPane.setMaxSize(previewW, previewH);
        VBox.setVgrow(previewPane, Priority.NEVER);

        startShimmer(previewW, previewH);

        Label nameLabel = new Label(mdxFile.getFileName().toString());
        nameLabel.getStyleClass().add("asset-name");
        nameLabel.setWrapText(true);

        Label pathLabel = new Label(toRelativePath(rootDirectory, mdxFile));
        pathLabel.getStyleClass().add("asset-path");
        pathLabel.setWrapText(true);

        // Rich tooltip — updated once the model loads; path-only in the meantime
        final Tooltip cardTooltip = new Tooltip(mdxFile.toString());
        cardTooltip.setShowDelay(Duration.millis(400));
        Tooltip.install(this, cardTooltip);

        getChildren().addAll(previewPane, nameLabel, pathLabel);
        setAlignment(Pos.TOP_LEFT);

        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                final ModelDetailDialog dialog = new ModelDetailDialog(
                        getScene().getWindow(), mdxFile, rootDirectory, previewFactory);
                dialog.show();
            }
        });

        loadPreviewAsync(mdxFile, rootDirectory, previewFactory, executor, cardTooltip);
    }

    private void startShimmer(final double w, final double h) {
        // Solid background tile
        final Rectangle base = new Rectangle(w, h);
        base.getStyleClass().add("shimmer-base");
        base.setArcWidth(10);
        base.setArcHeight(10);

        // Translucent sweep that travels left → right
        final double glowW = w * 0.55;
        final Rectangle glow = new Rectangle(glowW, h);
        glow.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.TRANSPARENT),
                new Stop(0.5, Color.rgb(255, 255, 255, 0.09)),
                new Stop(1.0, Color.TRANSPARENT)));

        // Clip so the glow doesn't bleed outside the rounded card
        final Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        previewPane.setClip(clip);

        shimmerTimeline = new Timeline(
                new KeyFrame(Duration.ZERO,        new KeyValue(glow.translateXProperty(), -glowW)),
                new KeyFrame(Duration.seconds(1.4), new KeyValue(glow.translateXProperty(), w + glowW)));
        shimmerTimeline.setCycleCount(Timeline.INDEFINITE);
        shimmerTimeline.play();

        previewPane.getChildren().setAll(base, glow);
    }

    private void stopShimmer() {
        if (shimmerTimeline != null) {
            shimmerTimeline.stop();
            shimmerTimeline = null;
        }
        previewPane.setClip(null);
    }

    private void loadPreviewAsync(
            final Path mdxFile,
            final Path rootDirectory,
            final MdxPreviewFactory previewFactory,
            final Executor executor,
            final Tooltip cardTooltip
    ) {
        CompletableFuture
                .supplyAsync(() -> parseModel(previewFactory, mdxFile), executor)
                .thenAccept(model -> Platform.runLater(() -> {
                    renderModel(model, mdxFile, rootDirectory, previewFactory);
                    if (model != null) updateTooltip(cardTooltip, model, mdxFile);
                }))
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
            final double previewW = previewPane.getPrefWidth();
            final double previewH = previewPane.getPrefHeight();
            final SubScene subScene = previewFactory.buildSubScene(model, mdxFile, rootDirectory, previewW, previewH);
            stopShimmer();
            previewPane.getChildren().setAll(subScene);
        } catch (Exception ignored) {
            showError("Preview unavailable");
        }
    }

    private void showError(final String message) {
        stopShimmer();
        final Label error = new Label(message);
        error.getStyleClass().add("preview-error");
        previewPane.getChildren().setAll(error);
    }

    private static void updateTooltip(final Tooltip tt, final MdlxModel model, final Path mdxFile) {
        final int polyCount  = model.geosets.stream().mapToInt(g -> g.faces    != null ? g.faces.length    / 3 : 0).sum();
        final int vertCount  = model.geosets.stream().mapToInt(g -> g.vertices != null ? g.vertices.length / 3 : 0).sum();
        final int seqCount   = model.sequences.size();
        final int boneCount  = model.bones.size();
        long fileSize = 0;
        try { fileSize = Files.size(mdxFile); } catch (final IOException ignored) {}
        final String sizeStr = fileSize < 1024 ? fileSize + " B"
                             : fileSize < 1024 * 1024 ? String.format("%.1f KB", fileSize / 1024.0)
                             : String.format("%.2f MB", fileSize / (1024.0 * 1024));
        tt.setText(String.format(
                "%s%nPolys: %,d  Verts: %,d%nBones: %d  Seqs: %d%nSize: %s",
                mdxFile.toString(), polyCount, vertCount, boneCount, seqCount, sizeStr));
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
