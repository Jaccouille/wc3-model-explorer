package com.war3.viewer.app;

import com.hiveworkshop.rms.parsers.mdlx.MdlxModel;
import com.war3.viewer.app.datasource.GameDataSource;
import com.war3.viewer.app.settings.AppSettings;
import com.war3.viewer.app.settings.SettingsDialog;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class AssetBrowserPane extends BorderPane {
    private static final String[] SIZE_LABELS = {"Extra Large", "Large", "Medium", "Small"};
    private static final int[]    SIZE_VALUES = {352, 252, 192, 128};

    private final Stage ownerStage;

    private final TreeView<Path> directoryTree = new TreeView<>();
    private final TilePane tilePane = new TilePane();
    private int cardWidth = AppSettings.get().getThumbnailSize();
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

    // Advanced search index and filter UI
    private final ConcurrentHashMap<Path, ModelMetadata> modelIndex = new ConcurrentHashMap<>();
    private VBox      filterPanel;
    private TextField animField, texField;
    private TextField polyMinField, polyMaxField;
    private TextField sizeMinKbField, sizeMaxKbField;

    private enum PortraitFilter { ALL, MODELS_ONLY, PORTRAITS_ONLY }
    private PortraitFilter portraitFilter = PortraitFilter.MODELS_ONLY;

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

        String lastRoot = AppSettings.get().getLastRootDirectory();
        if (lastRoot != null && !lastRoot.isEmpty()) {
            Path lastRootPath = Path.of(lastRoot);
            if (Files.isDirectory(lastRootPath)) {
                setRootDirectory(lastRootPath);
            }
        }
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

        final ComboBox<String> portraitCombo = new ComboBox<>(
                FXCollections.observableArrayList("Models + Portraits", "Models only", "Portraits only"));
        portraitCombo.getSelectionModel().select(1); // default: Models only
        portraitCombo.setPrefWidth(150);
        portraitCombo.setOnAction(e -> {
            portraitFilter = switch (portraitCombo.getSelectionModel().getSelectedIndex()) {
                case 0 -> PortraitFilter.ALL;
                case 2 -> PortraitFilter.PORTRAITS_ONLY;
                default -> PortraitFilter.MODELS_ONLY;
            };
            searchDebounce.playFromStart();
        });

        final ToggleButton filterToggle = new ToggleButton("Filter ▼");
        filterToggle.selectedProperty().addListener((obs, was, now) -> {
            if (filterPanel != null) {
                filterPanel.setVisible(now);
                filterPanel.setManaged(now);
                filterToggle.setText(now ? "Filter ▲" : "Filter ▼");
                searchDebounce.playFromStart();
            }
        });

        // Thumbnail size picker
        final ComboBox<String> sizeCombo = new ComboBox<>(
                FXCollections.observableArrayList(SIZE_LABELS));
        int sizeIdx = 1; // default = Large
        for (int i = 0; i < SIZE_VALUES.length; i++) {
            if (SIZE_VALUES[i] == cardWidth) { sizeIdx = i; break; }
        }
        sizeCombo.getSelectionModel().select(sizeIdx);
        sizeCombo.setPrefWidth(120);
        sizeCombo.setOnAction(e -> {
            final int idx = sizeCombo.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && idx < SIZE_VALUES.length) {
                cardWidth = SIZE_VALUES[idx];
                AppSettings.get().setThumbnailSize(cardWidth);
                AppSettings.save();
                tilePane.setPrefTileWidth(cardWidth);
                refreshThumbnails();
            }
        });

        HBox.setHgrow(rootField, Priority.ALWAYS);

        topBar.getChildren().addAll(chooseRootButton, refreshButton, rootField, searchField, portraitCombo, filterToggle, sizeCombo, settingsButton);
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
        tilePane.setPrefTileWidth(cardWidth);
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

        filterPanel = buildFilterPanel();

        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        rightPane.getChildren().addAll(gridHeader, filterPanel, scrollPane);

        splitPane.getItems().addAll(leftPane, rightPane);
        return splitPane;
    }

    private VBox buildFilterPanel() {
        animField      = new TextField();
        animField.setPromptText("e.g. stand");
        animField.setPrefWidth(160);
        texField       = new TextField();
        texField.setPromptText("e.g. footman.blp");
        texField.setPrefWidth(160);
        polyMinField   = numericField("Min");
        polyMaxField   = numericField("Max");
        sizeMinKbField = numericField("Min KB");
        sizeMaxKbField = numericField("Max KB");

        final Button clearBtn = new Button("Clear");
        clearBtn.setOnAction(e -> clearAdvancedFilters());

        final HBox row1 = new HBox(6,
                new Label("Animation:"), animField,
                new Label("Texture path:"), texField,
                clearBtn);
        row1.setAlignment(Pos.CENTER_LEFT);

        final Separator sep = new Separator(Orientation.VERTICAL);
        sep.setPadding(new Insets(0, 4, 0, 4));
        final HBox row2 = new HBox(6,
                new Label("Polys:"), polyMinField, new Label("–"), polyMaxField,
                sep,
                new Label("Size (KB):"), sizeMinKbField, new Label("–"), sizeMaxKbField);
        row2.setAlignment(Pos.CENTER_LEFT);

        final VBox panel = new VBox(6, row1, row2);
        panel.setPadding(new Insets(6, 8, 6, 8));
        panel.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 4;");
        panel.setVisible(false);
        panel.setManaged(false);
        return panel;
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

        for (TextField tf : new TextField[]{animField, texField,
                polyMinField, polyMaxField, sizeMinKbField, sizeMaxKbField}) {
            tf.textProperty().addListener((obs, o, n) -> searchDebounce.playFromStart());
        }

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
        } else {
            String lastRoot = AppSettings.get().getLastRootDirectory();
            if (lastRoot != null && !lastRoot.isEmpty()) {
                Path lastRootPath = Path.of(lastRoot);
                if (Files.isDirectory(lastRootPath)) {
                    chooser.setInitialDirectory(lastRootPath.toFile());
                }
            }
        }

        File selected = chooser.showDialog(ownerStage);
        if (selected != null) {
            setRootDirectory(selected.toPath());
        }
    }

    private void setRootDirectory(final Path directory) {
        rootDirectory = directory.toAbsolutePath().normalize();
        rootField.setText(rootDirectory.toString());
        AppSettings.get().setLastRootDirectory(rootDirectory.toString());
        AppSettings.save();

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

        final FilterState fs = captureFilterState();
        int requestId = refreshCounter.incrementAndGet();
        statusLabel.setText("Scanning " + selectedDirectory + " ...");

        CompletableFuture
                .supplyAsync(() -> scanMdxFiles(selectedDirectory, query, fs), scanExecutor)
                .thenAccept(files -> Platform.runLater(() -> applyThumbnailResult(requestId, files)))
                .exceptionally(error -> {
                    Platform.runLater(() -> statusLabel.setText("Scan failed: " + error.getMessage()));
                    return null;
                });
    }

    private List<Path> scanMdxFiles(final Path selectedDirectory, final String query, final FilterState fs) {
        try (Stream<Path> stream = Files.walk(selectedDirectory)) {
            final List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isMdxFile)
                    .filter(path -> query.isEmpty() || path.getFileName().toString().toLowerCase(Locale.ROOT).contains(query))
                    .sorted(Comparator
                            .comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                            .thenComparing(Path::toString))
                    .toList();

            if (!fs.active()) return candidates;

            // Index any uncached files synchronously on this background thread
            final int total = candidates.size();
            final AtomicInteger indexed = new AtomicInteger(0);
            for (final Path path : candidates) {
                if (!modelIndex.containsKey(path)) {
                    modelIndex.put(path, buildMetadata(path));
                }
                final int i = indexed.incrementAndGet();
                Platform.runLater(() -> statusLabel.setText("Indexing… " + i + "/" + total));
            }

            return candidates.stream()
                    .filter(path -> passesAdvancedFilter(path, fs))
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private ModelMetadata buildMetadata(final Path path) {
        try {
            final MdlxModel m = previewFactory.loadModel(path);
            final long fileSize = Files.size(path);
            final int polyCount = m.geosets.stream()
                    .mapToInt(g -> g.faces != null ? g.faces.length / 3 : 0).sum();
            final List<String> animNames = m.sequences.stream()
                    .map(s -> s.name.toLowerCase(Locale.ROOT))
                    .toList();
            final List<String> texPaths = m.textures.stream()
                    .filter(t -> t.path != null && !t.path.isBlank())
                    .map(t -> t.path.toLowerCase(Locale.ROOT))
                    .toList();
            return new ModelMetadata(fileSize, polyCount, animNames, texPaths);
        } catch (Exception ignored) {
            return new ModelMetadata(0, 0, List.of(), List.of());
        }
    }

    private FilterState captureFilterState() {
        if (filterPanel == null || !filterPanel.isVisible()) {
            return new FilterState(false, "", "", 0, Integer.MAX_VALUE, 0, Long.MAX_VALUE);
        }
        final String animQ  = animField.getText()  == null ? "" : animField.getText().trim().toLowerCase(Locale.ROOT);
        final String texQ   = texField.getText()   == null ? "" : texField.getText().trim().toLowerCase(Locale.ROOT);
        final int polyMin   = parseOrDefault(polyMinField.getText(), 0);
        final int polyMax   = parseOrDefault(polyMaxField.getText(), Integer.MAX_VALUE);
        final int sizeMinKb = parseOrDefault(sizeMinKbField.getText(), 0);
        final int sizeMaxKb = parseOrDefault(sizeMaxKbField.getText(), Integer.MAX_VALUE / 1024);
        final long sizeMin  = (long) sizeMinKb * 1024;
        final long sizeMax  = sizeMaxKb >= Integer.MAX_VALUE / 1024 ? Long.MAX_VALUE : (long) sizeMaxKb * 1024;

        final boolean active = !animQ.isEmpty() || !texQ.isEmpty()
                || polyMin > 0 || polyMax < Integer.MAX_VALUE
                || sizeMin > 0 || sizeMax < Long.MAX_VALUE;

        return new FilterState(active, animQ, texQ, polyMin, polyMax, sizeMin, sizeMax);
    }

    private boolean passesAdvancedFilter(final Path path, final FilterState fs) {
        final ModelMetadata meta = modelIndex.get(path);
        if (meta == null) return false;
        if (!fs.animQuery().isEmpty() && meta.animNames().stream().noneMatch(a -> a.contains(fs.animQuery()))) return false;
        if (!fs.texQuery().isEmpty()  && meta.texPaths().stream().noneMatch(t -> t.contains(fs.texQuery()))) return false;
        if (meta.polyCount() < fs.polyMin() || meta.polyCount() > fs.polyMax()) return false;
        if (meta.fileSize()  < fs.sizeMinBytes() || meta.fileSize() > fs.sizeMaxBytes()) return false;
        return true;
    }

    private void clearAdvancedFilters() {
        animField.clear();
        texField.clear();
        polyMinField.clear();
        polyMaxField.clear();
        sizeMinKbField.clear();
        sizeMaxKbField.clear();
        searchDebounce.playFromStart();
    }

    private boolean isMdxFile(final Path file) {
        final String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".mdx")) return false;
        final boolean isPortrait = name.contains("_portrait");
        return switch (portraitFilter) {
            case ALL             -> true;
            case MODELS_ONLY     -> !isPortrait;
            case PORTRAITS_ONLY  -> isPortrait;
        };
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

        statusLabel.setText("Loading 0 / " + files.size() + " models…");

        final int total = files.size();
        final int[] idx = {0};
        new AnimationTimer() {
            private static final int BATCH = 3;

            @Override
            public void handle(final long now) {
                if (requestId != refreshCounter.get()) {
                    stop();
                    return;
                }
                if (idx[0] >= total) {
                    stop();
                    statusLabel.setText("Loaded " + total + " model thumbnails.");
                    return;
                }
                final int end = Math.min(idx[0] + BATCH, total);
                for (int i = idx[0]; i < end; i++) {
                    tilePane.getChildren().add(
                            new ModelCard(files.get(i), rootDirectory, previewFactory, previewExecutor, cardWidth));
                }
                statusLabel.setText("Loading " + end + " / " + total + " models…");
                idx[0] = end;
            }
        }.start();
    }

    public void shutdown() {
        scanExecutor.shutdownNow();
        previewExecutor.shutdownNow();
    }

    private static int parseOrDefault(final String text, final int defaultValue) {
        if (text == null || text.isBlank()) return defaultValue;
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException ignored) { return defaultValue; }
    }

    private static TextField numericField(final String prompt) {
        final TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setPrefWidth(70);
        tf.setTextFormatter(new TextFormatter<>(change ->
                change.getText().matches("[0-9]*") ? change : null));
        return tf;
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

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private record ModelMetadata(long fileSize, int polyCount,
                                  List<String> animNames, List<String> texPaths) {}

    private record FilterState(boolean active, String animQuery, String texQuery,
                                int polyMin, int polyMax, long sizeMinBytes, long sizeMaxBytes) {}

    private static final class DirectoryTreeItem extends TreeItem<Path> {
        private boolean childrenLoaded;

        private DirectoryTreeItem(final Path value) {
            super(value);
        }
    }
}
