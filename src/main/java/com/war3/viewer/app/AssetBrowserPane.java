package com.war3.viewer.app;

import com.war3.viewer.app.datasource.GameDataSource;
import com.war3.viewer.app.settings.SettingsDialog;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class AssetBrowserPane extends BorderPane {
    private final Stage ownerStage;

    private final TreeView<Path> directoryTree = new TreeView<>();
    private final TilePane tilePane = new TilePane();
    private final TextField rootField = new TextField();
    private final TextField searchField = new TextField();
    private final Label statusLabel = new Label("Select a root directory to start browsing MDX assets.");
    private final Label dataSourceLabel = new Label();

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("mdx-scan"));
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            namedThreadFactory("mdx-preview")
    );

    private final MdxPreviewFactory previewFactory = new MdxPreviewFactory();
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(240));
    private final AtomicInteger refreshCounter = new AtomicInteger();

    private Path rootDirectory;

    public AssetBrowserPane(final Stage ownerStage) {
        this.ownerStage = ownerStage;
        getStyleClass().add("app-root");
        setPadding(new Insets(14));

        setTop(buildTopBar());
        setCenter(buildMainPane());
        setBottom(buildFooter());

        configureInteractions();
        refreshDataSourceStatus();
    }

    private HBox buildTopBar() {
        HBox topBar = new HBox(10);
        topBar.getStyleClass().add("top-bar");
        topBar.setPadding(new Insets(0, 0, 12, 0));

        Button chooseRootButton = new Button("Choose Root");
        chooseRootButton.getStyleClass().add("primary-button");
        chooseRootButton.setOnAction(event -> chooseRootDirectory());

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(event -> refreshThumbnails());

        Button settingsButton = new Button("Settings");
        settingsButton.setOnAction(event -> {
            new SettingsDialog(ownerStage).showAndWait().ifPresent(changed -> {
                refreshDataSourceStatus();
                if (Boolean.TRUE.equals(changed)) {
                    refreshThumbnails();
                }
            });
        });

        rootField.setPromptText("No root directory selected");
        rootField.setEditable(false);
        rootField.getStyleClass().add("root-field");

        searchField.setPromptText("Search model names...");
        searchField.getStyleClass().add("search-field");
        searchField.setPrefWidth(260);

        HBox.setHgrow(rootField, Priority.ALWAYS);

        topBar.getChildren().addAll(chooseRootButton, refreshButton, rootField, searchField, settingsButton);
        return topBar;
    }

    private SplitPane buildMainPane() {
        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.23);

        VBox leftPane = new VBox(10);
        leftPane.setPadding(new Insets(8));
        leftPane.getStyleClass().add("left-pane");

        Label treeHeader = new Label("Folders");
        treeHeader.getStyleClass().add("panel-title");

        directoryTree.getStyleClass().add("folder-tree");
        directoryTree.setShowRoot(true);
        VBox.setVgrow(directoryTree, Priority.ALWAYS);
        configureTreeCellFactory();

        leftPane.getChildren().addAll(treeHeader, directoryTree);

        tilePane.setHgap(14);
        tilePane.setVgap(14);
        tilePane.setPadding(new Insets(8));
        tilePane.setPrefTileWidth(252);
        tilePane.setPrefColumns(5);

        ScrollPane scrollPane = new ScrollPane(tilePane);
        scrollPane.getStyleClass().add("thumbnail-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            tilePane.setPrefWidth(Math.max(0, newBounds.getWidth() - 18));
        });

        VBox rightPane = new VBox(10);
        rightPane.setPadding(new Insets(8));
        rightPane.getStyleClass().add("right-pane");

        Label gridHeader = new Label("Model Thumbnails");
        gridHeader.getStyleClass().add("panel-title");

        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        rightPane.getChildren().addAll(gridHeader, scrollPane);

        splitPane.getItems().addAll(leftPane, rightPane);
        return splitPane;
    }

    private VBox buildFooter() {
        statusLabel.getStyleClass().add("status-text");
        dataSourceLabel.getStyleClass().add("status-text");
        VBox footer = new VBox(4, statusLabel, dataSourceLabel);
        footer.setPadding(new Insets(10, 0, 0, 0));
        return footer;
    }

    private void refreshDataSourceStatus() {
        if (GameDataSource.get().isEmpty()) {
            dataSourceLabel.setText("Game data: not configured — textures will be missing. Open Settings to add a CASC or MPQ source.");
            dataSourceLabel.setStyle("-fx-text-fill: #b05000;");
        } else {
            dataSourceLabel.setText("Game data: configured.");
            dataSourceLabel.setStyle("-fx-text-fill: #1a6b1a;");
        }
    }

    private void configureInteractions() {
        searchDebounce.setOnFinished(event -> refreshThumbnails());
        searchField.textProperty().addListener((obs, oldText, newText) -> searchDebounce.playFromStart());

        directoryTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> refreshThumbnails());
    }

    private void configureTreeCellFactory() {
        directoryTree.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(final Path item, final boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }

                TreeItem<Path> treeItem = getTreeItem();
                if (treeItem == null || treeItem.getParent() == null) {
                    setText(item.toString());
                    return;
                }

                Path fileName = item.getFileName();
                setText(fileName == null ? item.toString() : fileName.toString());
            }
        });
    }

    private void chooseRootDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Warcraft 3 Models Directory");

        if (rootDirectory != null && Files.isDirectory(rootDirectory)) {
            chooser.setInitialDirectory(rootDirectory.toFile());
        }

        File selected = chooser.showDialog(ownerStage);
        if (selected != null) {
            setRootDirectory(selected.toPath());
        }
    }

    private void setRootDirectory(final Path directory) {
        rootDirectory = directory.toAbsolutePath().normalize();
        rootField.setText(rootDirectory.toString());

        DirectoryTreeItem rootItem = createDirectoryItem(rootDirectory);
        directoryTree.setRoot(rootItem);
        directoryTree.getSelectionModel().select(rootItem);
        rootItem.setExpanded(true);

        refreshThumbnails();
    }

    private DirectoryTreeItem createDirectoryItem(final Path directory) {
        DirectoryTreeItem item = new DirectoryTreeItem(directory);

        if (hasChildDirectories(directory)) {
            item.getChildren().add(new TreeItem<>());
        }

        item.expandedProperty().addListener((obs, wasExpanded, isNowExpanded) -> {
            if (isNowExpanded) {
                loadChildren(item);
            }
        });

        return item;
    }

    private boolean hasChildDirectories(final Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.anyMatch(Files::isDirectory);
        } catch (IOException ignored) {
            return false;
        }
    }

    private void loadChildren(final DirectoryTreeItem item) {
        if (item.childrenLoaded) {
            return;
        }

        item.childrenLoaded = true;
        item.getChildren().clear();

        try (Stream<Path> stream = Files.list(item.getValue())) {
            ObservableList<TreeItem<Path>> children = FXCollections.observableArrayList(
                    stream.filter(Files::isDirectory)
                            .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                            .map(this::createDirectoryItem)
                            .toList()
            );
            item.getChildren().setAll(children);
        } catch (IOException ignored) {
        }
    }

    private void refreshThumbnails() {
        if (rootDirectory == null) {
            return;
        }

        TreeItem<Path> selectedItem = directoryTree.getSelectionModel().getSelectedItem();
        Path selectedDirectory = selectedItem != null && selectedItem.getValue() != null
                ? selectedItem.getValue()
                : rootDirectory;

        String query = searchField.getText() == null
                ? ""
                : searchField.getText().trim().toLowerCase(Locale.ROOT);

        int requestId = refreshCounter.incrementAndGet();
        statusLabel.setText("Scanning " + selectedDirectory + " ...");

        CompletableFuture
                .supplyAsync(() -> scanMdxFiles(selectedDirectory, query), scanExecutor)
                .thenAccept(files -> Platform.runLater(() -> applyThumbnailResult(requestId, files)))
                .exceptionally(error -> {
                    Platform.runLater(() -> statusLabel.setText("Scan failed: " + error.getMessage()));
                    return null;
                });
    }

    private List<Path> scanMdxFiles(final Path selectedDirectory, final String query) {
        try (Stream<Path> stream = Files.walk(selectedDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isMdxFile)
                    .filter(path -> query.isEmpty() || path.getFileName().toString().toLowerCase(Locale.ROOT).contains(query))
                    .sorted(Comparator
                            .comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                            .thenComparing(Path::toString))
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private boolean isMdxFile(final Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mdx");
    }

    private void applyThumbnailResult(final int requestId, final List<Path> files) {
        if (requestId != refreshCounter.get()) {
            return;
        }

        tilePane.getChildren().clear();

        if (files.isEmpty()) {
            Label empty = new Label("No .mdx files found for this folder and filter.");
            empty.getStyleClass().add("empty-text");
            tilePane.getChildren().add(empty);
            statusLabel.setText("No models found.");
            return;
        }

        for (Path file : files) {
            tilePane.getChildren().add(new ModelCard(file, rootDirectory, previewFactory, previewExecutor));
        }

        statusLabel.setText("Loaded " + files.size() + " model thumbnails.");
    }

    public void shutdown() {
        scanExecutor.shutdownNow();
        previewExecutor.shutdownNow();
    }

    private static ThreadFactory namedThreadFactory(final String baseName) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(baseName + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class DirectoryTreeItem extends TreeItem<Path> {
        private boolean childrenLoaded;

        private DirectoryTreeItem(final Path value) {
            super(value);
        }
    }
}
