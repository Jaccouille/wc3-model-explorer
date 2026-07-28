package com.hiveworkshop.ui;

import static com.hiveworkshop.i18n.Messages.get;
import static com.hiveworkshop.i18n.Messages.fmt;
import com.hiveworkshop.AppVersion;
import com.hiveworkshop.i18n.Messages;
import com.hiveworkshop.model.*;
import com.hiveworkshop.parser.*;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.NumberFormat;

import java.util.ArrayList;
import java.util.Collections;

import java.util.List;
import java.util.Locale;

import java.util.stream.Collectors;

public final class MainWindow extends JFrame {
    private enum TagState {
        NEUTRAL, INCLUDE, EXCLUDE;
        TagState next() {
            return switch (this) {
                case NEUTRAL -> INCLUDE;
                case INCLUDE -> EXCLUDE;
                case EXCLUDE -> NEUTRAL;
            };
        }
    }
    /** Shared last-used directory for all file choosers in the app. */
    static java.io.File lastChooserDir;
    private MapArchiveSource currentMapSource;

    private final JTextField rootField = new JTextField();
    private final JTextField searchField = new JTextField();
    private final JComboBox<PortraitFilter> portraitFilterCombo = new JComboBox<>(PortraitFilter.values());
    private final JComboBox<ThumbnailSize> thumbnailSizeCombo = new JComboBox<>(ThumbnailSize.values());
    private final JComboBox<String> thumbnailTeamColorCombo = new JComboBox<>(TeamColorOptions.labels());
    private final JButton browseButton = new JButton(get("main.browse"));
    private final JButton stopButton = new JButton(get("main.stop"));
    private final JButton scanButton = new JButton(get("main.scan"));
    private final JButton settingsButton = new JButton(get("main.settings"));
    private final JToggleButton favoritesToggle = new JToggleButton(get("main.favorites"));
    private final JButton recentButton = new JButton(get("main.recent"));
    private final JComboBox<SortOrder> sortCombo = new JComboBox<>(SortOrder.values());
    private final JToggleButton advancedFiltersToggle = new JToggleButton(get("main.advancedFilters"));
    private final JPanel advancedFiltersPanel = new JPanel(new BorderLayout());
    private final JTabbedPane advancedFiltersTabs = new JTabbedPane(JTabbedPane.TOP);
    private final JPanel filtersGridPanel = new JPanel(new GridLayout(0, 4, 8, 6));
    private final JTextField animationFilterField = new JTextField();
    private final JTextField textureFilterField = new JTextField();
    private final JTextField minPolygonsField = new JTextField();
    private final JTextField maxPolygonsField = new JTextField();
    private final JPanel tagButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    private final java.util.Map<String, TagState> tagStates = new java.util.LinkedHashMap<>();
    private final JTextField minSizeKbField = new JTextField();
    private final JTextField maxSizeKbField = new JTextField();
    private final JLabel searchLabel = new JLabel(get("main.search"));
    private final JLabel sortLabel = new JLabel(get("main.sort"));
    private final JLabel teamLabel = new JLabel(get("main.team"));
    private final JLabel statusLabel = new JLabel(get("main.chooseDirectory"));
    private final JLabel dataSourceLabel = new JLabel();
    private final DefaultListModel<ModelAsset> listModel = new DefaultListModel<>();
    private final JList<ModelAsset> assetList = new JList<>(listModel);
    private final List<ModelAsset> allAssets = new ArrayList<>();
    private final AppSettings settings = AppSettings.loadDefault();
    private int baseCardWidth;
    private int baseCardHeight;
    private Timer progressiveLoadTimer;
    private SwingWorker<?, ?> currentScanWorker;
    private volatile java.util.concurrent.atomic.AtomicBoolean scanCancelled;
    private ThumbnailRenderer thumbnailRenderer;
    private Timer shimmerTimer;
    private int pendingThumbnails;
    private int totalThumbnails;

    public MainWindow() {
        super(fmt("main.title", AppVersion.get()));
        setIconImage(new ImageIcon(MainWindow.class.getResource("/images/icon.png")).getImage());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        buildUi();
        wireEvents();
        restoreSettings();
        setSize(new Dimension(1000, 700));
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (thumbnailRenderer != null) thumbnailRenderer.shutdown();
                if (currentMapSource != null) {
                    currentMapSource.close();
                    currentMapSource = null;
                }
                GameDataSource.getInstance().clearMapSource();
            }
        });
    }

    private void buildUi() {
        JPanel rootPanel = new JPanel(new BorderLayout(10, 10));
        rootPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        JPanel browseRow = new JPanel(new BorderLayout(8, 8));
        browseRow.add(rootField, BorderLayout.CENTER);

        stopButton.setVisible(false);
        JPanel browseButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        browseButtons.add(browseButton);
        browseButtons.add(recentButton);
        browseButtons.add(scanButton);
        browseButtons.add(stopButton);
        browseButtons.add(settingsButton);
        browseRow.add(browseButtons, BorderLayout.EAST);
        topPanel.add(browseRow, BorderLayout.NORTH);

        JPanel filterRow = new JPanel(new BorderLayout(8, 0));
        filterRow.add(searchLabel, BorderLayout.WEST);
        filterRow.add(searchField, BorderLayout.CENTER);
        JPanel filterControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        filterControls.add(sortLabel);
        filterControls.add(sortCombo);
        filterControls.add(thumbnailSizeCombo);
        filterControls.add(teamLabel);
        filterControls.add(thumbnailTeamColorCombo);
        filterControls.add(favoritesToggle);
        filterControls.add(advancedFiltersToggle);
        filterRow.add(filterControls, BorderLayout.EAST);
        topPanel.add(filterRow, BorderLayout.SOUTH);
        buildAdvancedFiltersPanel();
        advancedFiltersPanel.setVisible(false);

        assetList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        assetList.setCellRenderer(new AssetCellRenderer());
        assetList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        assetList.setVisibleRowCount(-1);
        assetList.setDragEnabled(true);
        assetList.setTransferHandler(new AssetFileTransferHandler());
        JScrollPane listScrollPane = new JScrollPane(assetList);
        listScrollPane.getViewport().addChangeListener(e -> adjustCardWidth(listScrollPane));

        JPanel topContainer = new JPanel(new BorderLayout(0, 8));
        topContainer.add(topPanel, BorderLayout.NORTH);
        topContainer.add(advancedFiltersPanel, BorderLayout.CENTER);

        rootPanel.add(topContainer, BorderLayout.NORTH);
        rootPanel.add(listScrollPane, BorderLayout.CENTER);
        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        bottomBar.add(statusLabel, BorderLayout.CENTER);
        dataSourceLabel.setForeground(new Color(120, 130, 140));
        dataSourceLabel.setFont(dataSourceLabel.getFont().deriveFont(11f));
        bottomBar.add(dataSourceLabel, BorderLayout.EAST);
        rootPanel.add(bottomBar, BorderLayout.SOUTH);
        setContentPane(rootPanel);
    }

    private void wireEvents() {
        browseButton.addActionListener(event -> chooseDirectory());
        scanButton.addActionListener(event -> startScan(false));
        stopButton.addActionListener(event -> stopScan());
        settingsButton.addActionListener(event -> openSettings());
        recentButton.addActionListener(event -> showRecentFoldersPopup());
        favoritesToggle.addActionListener(event -> applyFilter());
        rootField.addActionListener(event -> startScan(false));
        advancedFiltersToggle.addActionListener(event -> {
            advancedFiltersPanel.setVisible(advancedFiltersToggle.isSelected());
            revalidate();
            repaint();
        });
        sortCombo.addActionListener(event -> applyFilter());
        portraitFilterCombo.addActionListener(event -> {
            settings.setPortraitFilter((PortraitFilter) portraitFilterCombo.getSelectedItem());
            settings.save();
            applyFilter();
        });
        thumbnailSizeCombo.addActionListener(event -> {
            settings.setThumbnailSize((ThumbnailSize) thumbnailSizeCombo.getSelectedItem());
            settings.save();
            updateCardSizing();
            applyFilter();
        });
        thumbnailTeamColorCombo.addActionListener(event -> {
            int teamColor = thumbnailTeamColorCombo.getSelectedIndex();
            settings.setThumbnailTeamColor(teamColor);
            settings.save();
            if (thumbnailRenderer != null) {
                thumbnailRenderer.setTeamColor(teamColor);
            }
            applyFilter();
        });
        wireFieldForFiltering(animationFilterField);
        wireFieldForFiltering(textureFilterField);
        wireFieldForFiltering(minPolygonsField);
        wireFieldForFiltering(maxPolygonsField);
        wireFieldForFiltering(minSizeKbField);
        wireFieldForFiltering(maxSizeKbField);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFilter();
            }
        });

        assetList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && assetList.getSelectedValue() != null) {
                    showModelDetails(assetList.getSelectedValue());
                }
            }
            @Override
            public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = assetList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                if (!assetList.isSelectedIndex(idx)) {
                    assetList.setSelectedIndex(idx);
                }
                List<ModelAsset> selectedAssets = selectedAssets();
                if (selectedAssets.isEmpty()) return;
                showAssetContextMenu(selectedAssets, e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.addChoosableFileFilter(
                new javax.swing.filechooser.FileNameExtensionFilter(
                        get("main.wc3Maps"), "w3x", "w3m"));
        String rootText = rootField.getText().trim();
        if (!rootText.isEmpty()) {
            try {
                Path rootPath = Path.of(rootText);
                // For map files, start in the parent directory
                if (java.nio.file.Files.isRegularFile(rootPath)) {
                    chooser.setCurrentDirectory(rootPath.getParent().toFile());
                } else {
                    chooser.setCurrentDirectory(rootPath.toFile());
                }
            } catch (InvalidPathException ignored) {}
        } else if (lastChooserDir != null) {
            chooser.setCurrentDirectory(lastChooserDir);
        }
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            lastChooserDir = selected.isDirectory() ? selected : selected.getParentFile();
            rootField.setText(selected.toPath().toString());
            saveCurrentRootDirectory();
            startScan(false);
        }
    }

    private void startScan(boolean forceRefresh) {
        String rootText = rootField.getText().trim();
        if (rootText.isEmpty()) {
            statusLabel.setText(get("main.pleaseChoose"));
            return;
        }

        final Path root;
        try {
            root = Path.of(rootText);
        } catch (InvalidPathException ex) {
            statusLabel.setText(get("main.invalidPath"));
            return;
        }
        saveCurrentRootDirectory();

        statusLabel.setText(get("main.scanning"));
        scanButton.setEnabled(false);
        browseButton.setEnabled(false);
        stopButton.setVisible(true);
        scanCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        listModel.clear();
        allAssets.clear();
        stopProgressiveLoader();
        if (thumbnailRenderer != null) {
            applySettingsToThumbnailRenderer();
            if (forceRefresh) thumbnailRenderer.clearCache();
        }

        final boolean isMap = MapArchiveSource.isMapFile(root);
        final java.util.concurrent.atomic.AtomicBoolean cancelled = scanCancelled;
        final boolean parseTags = settings.tagsEnabled();

        SwingWorker<List<ModelAsset>, String> worker = new SwingWorker<>() {
            @Override
            protected List<ModelAsset> doInBackground() throws Exception {
                if (isMap) {
                    publish(Messages.get("main.openingMap"));
                    // Close previous map source
                    if (currentMapSource != null) {
                        currentMapSource.close();
                        currentMapSource = null;
                    }
                    GameDataSource.getInstance().clearMapSource();

                    MapArchiveSource mapSource = MapArchiveSource.open(root);
                    currentMapSource = mapSource;
                    GameDataSource.getInstance().setMapSource(mapSource.getDataSource());

                    return ModelScanner.scan(mapSource.getTempDir(), true, (done, total) -> {
                        if (done % 5 == 0 || done == total) {
                            publish(fmt("main.parsingModels", done, total));
                        }
                    }, cancelled, parseTags);
                } else {
                    // Directory mode — clear any previous map source
                    if (currentMapSource != null) {
                        currentMapSource.close();
                        currentMapSource = null;
                    }
                    GameDataSource.getInstance().clearMapSource();

                    return ModelScanner.scan(root, forceRefresh, (done, total) -> {
                        if (done % 5 == 0 || done == total) {
                            publish(fmt("main.parsingModels", done, total));
                        }
                    }, cancelled, parseTags);
                }
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                scanButton.setEnabled(true);
                browseButton.setEnabled(true);
                stopButton.setVisible(false);
                currentScanWorker = null;
                try {
                    if (isCancelled()) {
                        statusLabel.setText(Messages.get("main.scanStopped"));
                        return;
                    }
                    allAssets.addAll(get());
                    rebuildTagButtons();
                    applyFilter();
                } catch (java.util.concurrent.CancellationException ex) {
                    statusLabel.setText(Messages.get("main.scanStopped"));
                } catch (Exception ex) {
                    statusLabel.setText(Messages.get("main.scanFailed"));
                    showErrorDialog(MainWindow.this,
                            fmt("main.failedToScan", ex.getMessage()), Messages.get("main.scanError"));
                }
            }
        };
        currentScanWorker = worker;
        worker.execute();
    }

    private void stopScan() {
        if (scanCancelled != null) {
            scanCancelled.set(true);
        }
        if (currentScanWorker != null && !currentScanWorker.isDone()) {
            currentScanWorker.cancel(true);
        }
    }

    private void applyFilter() {
        String needle = searchField.getText().trim().toLowerCase(Locale.ROOT);
        PortraitFilter portraitFilter = (PortraitFilter) portraitFilterCombo.getSelectedItem();
        if (portraitFilter == null) {
            portraitFilter = PortraitFilter.MODELS_ONLY;
        }
        String animationNeedle = animationFilterField.getText().trim().toLowerCase(Locale.ROOT);
        String textureNeedle = textureFilterField.getText().trim().toLowerCase(Locale.ROOT);
        Integer minPolygons = parseNonNegativeInt(minPolygonsField.getText());
        Integer maxPolygons = parseNonNegativeInt(maxPolygonsField.getText());
        Long minSizeBytes = parseKilobytesToBytes(minSizeKbField.getText());
        Long maxSizeBytes = parseKilobytesToBytes(maxSizeKbField.getText());

        boolean showFavOnly = favoritesToggle.isSelected();
        List<ModelAsset> filtered = allAssets.stream()
                .filter(asset -> !showFavOnly || settings.isFavorite(asset.path().toAbsolutePath().toString()))
                .filter(asset -> needle.isEmpty()
                        || asset.fileName().toLowerCase(Locale.ROOT).contains(needle)
                        || asset.metadata().modelName().toLowerCase(Locale.ROOT).contains(needle))
                .filter(portraitFilter::allows)
                .filter(asset -> asset.metadata().hasAnimationContaining(animationNeedle))
                .filter(asset -> asset.metadata().hasTextureContaining(textureNeedle))
                .filter(this::matchesTagFilter)
                .filter(asset -> polygonRangeMatches(asset, minPolygons, maxPolygons))
                .filter(asset -> minSizeBytes == null || asset.fileSizeBytes() >= minSizeBytes)
                .filter(asset -> maxSizeBytes == null || asset.fileSizeBytes() <= maxSizeBytes)
                .sorted(currentSortOrder().comparator())
                .collect(Collectors.toList());
        renderAssetsProgressively(filtered);
    }

    private SortOrder currentSortOrder() {
        SortOrder selected = (SortOrder) sortCombo.getSelectedItem();
        return selected != null ? selected : SortOrder.NAME_ASC;
    }

    private void openSettings() {
        SettingsDialog dialog = new SettingsDialog(this, settings);
        dialog.setVisible(true);
    }

    /** Clears the thumbnail cache and re-renders all visible thumbnails. */
    public void clearThumbnailCache() {
        if (thumbnailRenderer != null) {
            thumbnailRenderer.clearCache();
        }
        if (!allAssets.isEmpty()) {
            applyFilter();
        }
    }

    /** Re-applies all translatable text after a locale change. */
    public void refreshLocale() {
        setTitle(fmt("main.title", AppVersion.get()));
        browseButton.setText(get("main.browse"));
        stopButton.setText(get("main.stop"));
        scanButton.setText(get("main.scan"));
        settingsButton.setText(get("main.settings"));
        favoritesToggle.setText(get("main.favorites"));
        recentButton.setText(get("main.recent"));
        advancedFiltersToggle.setText(get("main.advancedFilters"));
        searchLabel.setText(get("main.search"));
        sortLabel.setText(get("main.sort"));
        teamLabel.setText(get("main.team"));
        thumbnailTeamColorCombo.setToolTipText(get("main.thumbnailTeamColor"));

        // Repopulate enum combos so toString() picks up new locale
        SortOrder selectedSort = (SortOrder) sortCombo.getSelectedItem();
        sortCombo.removeAllItems();
        for (SortOrder s : SortOrder.values()) sortCombo.addItem(s);
        sortCombo.setSelectedItem(selectedSort);

        PortraitFilter selectedPortrait = (PortraitFilter) portraitFilterCombo.getSelectedItem();
        portraitFilterCombo.removeAllItems();
        for (PortraitFilter p : PortraitFilter.values()) portraitFilterCombo.addItem(p);
        portraitFilterCombo.setSelectedItem(selectedPortrait);

        ThumbnailSize selectedSize = (ThumbnailSize) thumbnailSizeCombo.getSelectedItem();
        thumbnailSizeCombo.removeAllItems();
        for (ThumbnailSize t : ThumbnailSize.values()) thumbnailSizeCombo.addItem(t);
        thumbnailSizeCombo.setSelectedItem(selectedSize);

        // Repopulate team color combo
        int selectedTeam = thumbnailTeamColorCombo.getSelectedIndex();
        thumbnailTeamColorCombo.removeAllItems();
        for (String label : TeamColorOptions.labels()) thumbnailTeamColorCombo.addItem(label);
        if (selectedTeam >= 0) thumbnailTeamColorCombo.setSelectedIndex(selectedTeam);

        // Rebuild advanced filters panel (has inline labels)
        buildAdvancedFiltersPanel();
        advancedFiltersPanel.revalidate();
        advancedFiltersPanel.repaint();

        // Refresh status bar and list
        updateDataSourceLabel();
        applyFilter();
    }

    private void restoreSettings() {
        rootField.setText(settings.lastRootDirectory());
        portraitFilterCombo.setSelectedItem(settings.portraitFilter());
        thumbnailSizeCombo.setSelectedItem(settings.thumbnailSize());
        thumbnailTeamColorCombo.setRenderer(new TeamColorComboRenderer(thumbnailTeamColorCombo, idx -> {
            int[] rgb = GameDataSource.getInstance().loadTeamColorRgb(idx, null, currentScanRoot());
            return rgb != null ? rgb : TeamColorOptions.fallbackRgb(idx);
        }));
        thumbnailTeamColorCombo.setSelectedIndex(settings.thumbnailTeamColor());
        thumbnailTeamColorCombo.setToolTipText(get("main.thumbnailTeamColor"));
        updateCardSizing();
        // Initialise data sources in background so startup is not blocked
        updateDataSourceLabel();
        new Thread(() -> {
            GameDataSource.getInstance().refresh(settings);
            SwingUtilities.invokeLater(this::updateDataSourceLabel);
        }, "DataSource-Init").start();
        String rootText = rootField.getText().trim();
        if (!rootText.isEmpty()) {
            startScan(false);
        }
    }

    /** Updates the data source indicator label in the status bar. */
    public void updateDataSourceLabel() {
        int count = GameDataSource.getInstance().getSourceCount();
        if (count > 0) {
            dataSourceLabel.setText(fmt("main.dataSourcesLoaded", count));
            dataSourceLabel.setForeground(new Color(60, 140, 60));
        } else {
            dataSourceLabel.setText(get("main.dataSourcesNone"));
            dataSourceLabel.setForeground(new Color(160, 120, 60));
        }
    }

    private void saveCurrentRootDirectory() {
        String root = rootField.getText().trim();
        settings.setLastRootDirectory(root);
        if (!root.isEmpty()) {
            // Record the folder (or parent of a map file) in recent folders
            try {
                Path p = Path.of(root);
                Path folder = java.nio.file.Files.isRegularFile(p) ? p.getParent() : p;
                if (folder != null) settings.addRecentFolder(folder.toString());
            } catch (InvalidPathException ignored) {}
        }
        settings.save();
        thumbnailTeamColorCombo.repaint();
    }

    private Path currentScanRoot() {
        if (currentMapSource != null) return currentMapSource.getTempDir();
        String rootText = rootField.getText().trim();
        if (rootText.isEmpty()) {
            return null;
        }
        try {
            return Path.of(rootText);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private void showAssetContextMenu(List<ModelAsset> assets, java.awt.Component comp, int x, int y) {
        javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
        boolean multiple = assets.size() > 1;

        // Copy path
        javax.swing.JMenuItem copyPathItem = new javax.swing.JMenuItem(multiple ? get("main.copyPaths") : get("main.copyPath"));
        copyPathItem.addActionListener(e -> {
            String path = assets.stream()
                    .map(a -> a.path().toAbsolutePath().toString())
                    .collect(Collectors.joining(System.lineSeparator()));
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(path), null);
        });
        popup.add(copyPathItem);

        javax.swing.JMenuItem copyFileItem = new javax.swing.JMenuItem(multiple ? get("main.copyFiles") : get("main.copyFile"));
        copyFileItem.addActionListener(e -> copyAssetFilesToClipboard(assets));
        popup.add(copyFileItem);

        javax.swing.JMenuItem copyWithTexItem = new javax.swing.JMenuItem(get("main.copyWithTextures"));
        copyWithTexItem.addActionListener(e -> copyModelsWithTextures(assets));
        popup.add(copyWithTexItem);

        // Open file location
        if (!multiple) {
            JMenuItem openLocItem = new JMenuItem(get("main.openFileLocation"));
            openLocItem.addActionListener(e -> {
                try {
                    java.awt.Desktop.getDesktop().open(assets.get(0).path().getParent().toFile());
                } catch (Exception ex) {
                    // Fallback for systems where Desktop.open doesn't work
                    try {
                        new ProcessBuilder("explorer", "/select,", assets.get(0).path().toAbsolutePath().toString()).start();
                    } catch (Exception ignored) {}
                }
            });
            popup.add(openLocItem);
        }

        // Toggle favorite
        {
            boolean allFav = assets.stream().allMatch(a -> settings.isFavorite(a.path().toAbsolutePath().toString()));
            JMenuItem favItem = new JMenuItem(allFav ? get("main.removeFavorite") : get("main.addFavorite"));
            favItem.addActionListener(e -> {
                for (ModelAsset a : assets) {
                    String absPath = a.path().toAbsolutePath().toString();
                    if (allFav) {
                        if (settings.isFavorite(absPath)) settings.toggleFavorite(absPath);
                    } else {
                        if (!settings.isFavorite(absPath)) settings.toggleFavorite(absPath);
                    }
                }
                settings.save();
                assetList.repaint();
                if (favoritesToggle.isSelected()) applyFilter();
            });
            popup.add(favItem);
            popup.addSeparator();
        }

        // Tags submenu — add/remove tags on selected models
        {
            javax.swing.JMenu tagsMenu = new javax.swing.JMenu(get("main.tagsMenu"));

            // Collect effective tags across all selected models
            java.util.Set<String> unionTags = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (ModelAsset a : assets) {
                unionTags.addAll(getEffectiveTags(a));
            }

            // "Add Tag" submenu: all known tags not already on ALL selected models + "New Tag..."
            javax.swing.JMenu addMenu = new javax.swing.JMenu(get("main.addTag"));
            java.util.Set<String> allKnownTags = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (ModelAsset a : allAssets) {
                allKnownTags.addAll(getEffectiveTags(a));
            }
            allKnownTags.addAll(settings.allCustomTagNames());

            // Collect tags present on ALL selected models
            java.util.Set<String> intersectionTags = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            intersectionTags.addAll(getEffectiveTags(assets.get(0)));
            for (int i = 1; i < assets.size(); i++) {
                intersectionTags.retainAll(getEffectiveTags(assets.get(i)));
            }

            // Show tags not already on all selected
            for (String tag : allKnownTags) {
                if (!intersectionTags.contains(tag)) {
                    JMenuItem item = new JMenuItem(tag);
                    item.addActionListener(e -> {
                        for (ModelAsset a : assets) {
                            List<String> effective = getEffectiveTags(a);
                            if (effective.stream().noneMatch(tag::equalsIgnoreCase)) {
                                settings.addCustomTag(a.path().toAbsolutePath().toString(), tag);
                            }
                        }
                        settings.save();
                        rebuildTagButtons();
                        applyFilter();
                    });
                    addMenu.add(item);
                }
            }
            if (addMenu.getItemCount() > 0) addMenu.addSeparator();
            JMenuItem newTagItem = new JMenuItem(get("main.newTag"));
            newTagItem.addActionListener(e -> {
                String newTag = JOptionPane.showInputDialog(this, get("main.newTagPrompt"), get("main.newTagTitle"),
                        JOptionPane.PLAIN_MESSAGE);
                if (newTag != null && !newTag.trim().isEmpty()) {
                    newTag = newTag.trim();
                    for (ModelAsset a : assets) {
                        settings.addCustomTag(a.path().toAbsolutePath().toString(), newTag);
                    }
                    settings.save();
                    rebuildTagButtons();
                    applyFilter();
                }
            });
            addMenu.add(newTagItem);
            tagsMenu.add(addMenu);

            // "Remove Tag" submenu: tags on the selected models
            if (!unionTags.isEmpty()) {
                javax.swing.JMenu removeMenu = new javax.swing.JMenu(get("main.removeTag"));
                for (String tag : unionTags) {
                    JMenuItem item = new JMenuItem(tag);
                    item.addActionListener(e -> {
                        for (ModelAsset a : assets) {
                            settings.removeCustomTag(a.path().toAbsolutePath().toString(), tag);
                        }
                        settings.save();
                        rebuildTagButtons();
                        applyFilter();
                    });
                    removeMenu.add(item);
                }
                tagsMenu.add(removeMenu);
            }

            // "Hide Tag" submenu (global hide)
            if (!unionTags.isEmpty()) {
                javax.swing.JMenu hideMenu = new javax.swing.JMenu(get("settings.tags.removeTag"));
                for (String tag : unionTags) {
                    JMenuItem item = new JMenuItem(tag);
                    item.addActionListener(e -> {
                        settings.addRemovedTag(tag);
                        settings.save();
                        rebuildTagButtons();
                        applyFilter();
                    });
                    hideMenu.add(item);
                }
                tagsMenu.add(hideMenu);
            }

            popup.add(tagsMenu);
            popup.addSeparator();
        }

        // External programs
        List<ExternalProgram> programs = settings.externalPrograms();
        if (!multiple && !programs.isEmpty()) {
            ModelAsset asset = assets.get(0);
            popup.addSeparator();
            if (programs.size() == 1) {
                ExternalProgram p = programs.get(0);
                javax.swing.JMenuItem item = new javax.swing.JMenuItem(fmt("main.openIn", p.name()));
                item.addActionListener(e -> openInExternalProgram(p, asset));
                popup.add(item);
            } else {
                javax.swing.JMenu submenu = new javax.swing.JMenu(get("main.openInSubmenu"));
                for (ExternalProgram p : programs) {
                    javax.swing.JMenuItem item = new javax.swing.JMenuItem(p.name());
                    item.addActionListener(e -> openInExternalProgram(p, asset));
                    submenu.add(item);
                }
                popup.add(submenu);
            }
        }

        // Delete file(s)
        popup.addSeparator();
        JMenuItem deleteItem = new JMenuItem(multiple ? get("main.deleteFiles") : get("main.deleteFile"));
        deleteItem.addActionListener(e -> {
            String message = multiple
                    ? fmt("main.deleteConfirmMultiple", assets.size())
                    : fmt("main.deleteConfirm", assets.get(0).fileName());
            int choice = JOptionPane.showConfirmDialog(this, message,
                    get("main.deleteConfirmTitle"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                for (ModelAsset a : assets) {
                    try {
                        java.nio.file.Files.deleteIfExists(a.path());
                        allAssets.remove(a);
                    } catch (Exception ex) {
                        System.err.println("Failed to delete " + a.path() + ": " + ex);
                    }
                }
                applyFilter();
            }
        });
        popup.add(deleteItem);

        popup.show(comp, x, y);
    }

    private void openInExternalProgram(ExternalProgram program, ModelAsset asset) {
        try {
            String modelPath = asset.path().toAbsolutePath().toString();
            String cmd = program.command();
            String arguments = program.arguments();

            List<String> args = new ArrayList<>();
            // Auto-detect .jar files and prepend "java -jar"
            if (cmd.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                args.add("java");
                args.add("-jar");
            }
            args.add(cmd);

            if (!arguments.isBlank()) {
                // Parse arguments, then substitute {file} in each token
                List<String> argTokens = parseCommand(arguments);
                for (String token : argTokens) {
                    args.add(token.contains("{file}") ? token.replace("{file}", modelPath) : token);
                }
            }
            // If {file} was never referenced, append the model path
            if (arguments.isBlank() || !arguments.contains("{file}")) {
                args.add(modelPath);
            }

            System.out.println("[ExternalProgram] Launching: " + args);
            new ProcessBuilder(args).start();
        } catch (Exception ex) {
            showErrorDialog(this,
                    fmt("main.failedToLaunch", program.name(), ex.getMessage()), get("main.error"));
        }
    }

    private void copyAssetFilesToClipboard(List<ModelAsset> assets) {
        List<File> files = assets.stream()
                .map(asset -> asset.path().toFile())
                .toList();
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new FileListTransferable(files), null);
    }

    /**
     * Copies the selected model files plus their custom (loose, on-disk) textures into a
     * chosen destination folder, recreating each texture's referenced relative path.
     * Textures that live inside CASC/MPQ archives, or that are replaceable/missing, are skipped.
     */
    private void copyModelsWithTextures(List<ModelAsset> assets) {
        if (assets.isEmpty()) return;

        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(get("main.copyWithTextures.chooseDest"));
        if (lastChooserDir != null) fc.setCurrentDirectory(lastChooserDir);
        if (fc.showDialog(this, get("main.copyWithTextures.select")) != JFileChooser.APPROVE_OPTION) return;
        lastChooserDir = fc.getSelectedFile();
        final Path dest = fc.getSelectedFile().toPath();
        final Path scanRoot = currentScanRoot();

        new Thread(() -> {
            int models = 0, textures = 0, skipped = 0;
            List<String> errors = new ArrayList<>();
            com.hiveworkshop.parser.GameDataSource gds = com.hiveworkshop.parser.GameDataSource.getInstance();
            for (ModelAsset asset : assets) {
                try {
                    Path modelSrc = asset.path();
                    java.nio.file.Files.createDirectories(dest);
                    java.nio.file.Files.copy(modelSrc, dest.resolve(asset.fileName()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    models++;

                    com.hiveworkshop.model.ReterasParsedModel pm =
                            com.hiveworkshop.parser.ReterasModelParser.parse(modelSrc);
                    Path modelDir = modelSrc.getParent();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (com.hiveworkshop.model.GeosetTexData td : pm.texData()) {
                        if (!td.layers().isEmpty()) {
                            for (var layer : td.layers()) {
                                switch (copyOneTexture(gds, layer.texturePath(), layer.replaceableId(),
                                        modelDir, scanRoot, dest, seen)) {
                                    case 1 -> textures++;
                                    case 0 -> skipped++;
                                    default -> { /* duplicate or blank — ignore */ }
                                }
                            }
                        } else {
                            switch (copyOneTexture(gds, td.texturePath(), td.replaceableId(),
                                    modelDir, scanRoot, dest, seen)) {
                                case 1 -> textures++;
                                case 0 -> skipped++;
                                default -> { }
                            }
                        }
                    }
                } catch (Exception ex) {
                    errors.add(asset.fileName() + ": " + ex.getMessage());
                }
            }
            final int fModels = models, fTex = textures, fSkip = skipped;
            SwingUtilities.invokeLater(() -> {
                String msg = fmt("main.copyWithTextures.done", fModels, fTex, fSkip);
                if (!errors.isEmpty()) msg += System.lineSeparator() + System.lineSeparator()
                        + String.join(System.lineSeparator(), errors);
                JOptionPane.showMessageDialog(this, msg, get("main.copyWithTextures"),
                        errors.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            });
        }, "CopyWithTextures").start();
    }

    /**
     * Copies a single texture reference to the destination, preserving its relative path.
     * @return 1 if copied, 0 if skipped (archive/replaceable/missing), -1 if blank or duplicate.
     */
    private static int copyOneTexture(com.hiveworkshop.parser.GameDataSource gds, String texPath,
            int replaceableId, Path modelDir, Path scanRoot, Path dest, java.util.Set<String> seen) {
        if (texPath == null || texPath.isBlank() || replaceableId > 0) return -1;
        if (!seen.add(texPath)) return -1;
        Path texSrc = gds.resolveDiskFile(texPath, modelDir, scanRoot);
        if (texSrc == null) return 0; // inside an archive or missing — skip per spec
        try {
            Path texDest = dest.resolve(texPath.replace('\\', '/'));
            if (texDest.getParent() != null) java.nio.file.Files.createDirectories(texDest.getParent());
            java.nio.file.Files.copy(texSrc, texDest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return 1;
        } catch (Exception ex) {
            return 0;
        }
    }

    private List<ModelAsset> selectedAssets() {
        List<ModelAsset> selected = assetList.getSelectedValuesList();
        return selected.isEmpty() ? Collections.emptyList() : selected;
    }

    /** Splits a command string into tokens, respecting double-quoted segments. */
    private static List<String> parseCommand(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    private final class AssetFileTransferHandler extends TransferHandler {
        @Override
        protected Transferable createTransferable(JComponent c) {
            List<ModelAsset> assets = selectedAssets();
            if (assets.isEmpty()) {
                return null;
            }
            return new FileListTransferable(assets.stream()
                    .map(asset -> asset.path().toFile())
                    .toList());
        }

        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }
    }

    private static final class FileListTransferable implements Transferable {
        private final List<File> files;

        private FileListTransferable(List<File> files) {
            this.files = files;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }

    private void showModelDetails(ModelAsset asset) {
        if (asset.metadata().isHd()) {
            JOptionPane.showMessageDialog(this,
                    fmt("main.hdUnsupportedDetail", asset.fileName()),
                    get("main.hdUnsupportedTitle"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path scanRoot = currentScanRoot();
        ModelViewerDialog dialog = new ModelViewerDialog(this, asset, scanRoot, settings);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showRecentFoldersPopup() {
        List<String> recent = settings.recentFolders();
        if (recent.isEmpty()) {
            JOptionPane.showMessageDialog(this, get("main.noRecentFolders"), get("main.recentFolders"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JPopupMenu popup = new JPopupMenu();
        for (String absPath : recent) {
            Path p = Path.of(absPath);
            String name = p.getFileName() != null ? p.getFileName().toString() : absPath;
            JMenuItem item = new JMenuItem(name);
            item.setToolTipText(absPath);
            item.addActionListener(e -> {
                if (!java.nio.file.Files.exists(p)) {
                    showErrorDialog(this, fmt("main.fileNotFound", absPath), get("main.error"));
                    return;
                }
                rootField.setText(absPath);
                saveCurrentRootDirectory();
                startScan(false);
            });
            popup.add(item);
        }
        popup.show(recentButton, 0, recentButton.getHeight());
    }

    private void renderAssetsProgressively(List<ModelAsset> filtered) {
        stopProgressiveLoader();
        listModel.clear();
        if (filtered.isEmpty()) {
            statusLabel.setText(fmt("main.showingModels", 0, allAssets.size()));
            return;
        }

        // Ensure thumbnail renderer exists
        ensureThumbnailRenderer();
        thumbnailRenderer.cancelPending();

        final Path root = currentScanRoot();

        pendingThumbnails = 0;
        totalThumbnails = 0;
        final int batchSize = 3;
        final int[] index = {0};
        progressiveLoadTimer = new Timer(16, event -> {
            int remaining = filtered.size() - index[0];
            int toAdd = Math.min(batchSize, remaining);
            for (int i = 0; i < toAdd; i++) {
                ModelAsset asset = filtered.get(index[0]++);
                listModel.addElement(asset);
                // Skip thumbnail rendering for unsupported HD models — the cell shows a badge instead.
                if (asset.metadata().isHd()) continue;
                // Queue thumbnail if not cached
                if (thumbnailRenderer.getThumbnail(asset.path()) == null) {
                    pendingThumbnails++;
                    totalThumbnails++;
                    thumbnailRenderer.request(asset, root, img -> {
                        pendingThumbnails--;
                        assetList.repaint();
                        updateThumbnailProgress();
                        if (pendingThumbnails <= 0) stopShimmerTimer();
                    });
                }
            }
            updateStatusLabel();
            if (index[0] >= filtered.size()) {
                stopProgressiveLoader();
            }
        });
        progressiveLoadTimer.setRepeats(true);
        progressiveLoadTimer.start();

        // Start shimmer repaint timer for loading animation
        startShimmerTimer();
    }

    private void ensureThumbnailRenderer() {
        if (thumbnailRenderer != null) return;
        thumbnailRenderer = new ThumbnailRenderer();
        applySettingsToThumbnailRenderer();
    }

    private void applySettingsToThumbnailRenderer() {
        if (thumbnailRenderer == null) return;
        String bgHex = settings.bgColor();
        if (bgHex != null && bgHex.length() == 6) {
            try {
                int r = Integer.parseInt(bgHex.substring(0, 2), 16);
                int g = Integer.parseInt(bgHex.substring(2, 4), 16);
                int b = Integer.parseInt(bgHex.substring(4, 6), 16);
                thumbnailRenderer.setBackgroundColor(r, g, b);
            } catch (NumberFormatException ignored) {}
        }
        thumbnailRenderer.setCameraAngles(settings.cameraYaw(), settings.cameraPitch());
        thumbnailRenderer.setAnimationName(settings.thumbnailAnimName());
        thumbnailRenderer.setTeamColor(settings.thumbnailTeamColor());
        ThumbnailQuality quality = settings.thumbnailQuality();
        thumbnailRenderer.setQuality(quality.renderSize(), quality.thumbSize());
    }

    private void startShimmerTimer() {
        if (shimmerTimer != null && shimmerTimer.isRunning()) return;
        shimmerTimer = new Timer(33, e -> assetList.repaint()); // ~30fps for shimmer animation
        shimmerTimer.setRepeats(true);
        shimmerTimer.start();
    }

    private void stopShimmerTimer() {
        if (shimmerTimer != null && shimmerTimer.isRunning()) shimmerTimer.stop();
        updateStatusLabel();
    }

    private void updateThumbnailProgress() {
        updateStatusLabel();
    }

    private void updateStatusLabel() {
        String text = fmt("main.showingModels", listModel.getSize(), allAssets.size());
        if (totalThumbnails > 0 && pendingThumbnails > 0) {
            int done = totalThumbnails - pendingThumbnails;
            text += fmt("main.thumbnailProgress", done, totalThumbnails);
        }
        statusLabel.setText(text);
    }

    private void stopProgressiveLoader() {
        if (progressiveLoadTimer != null && progressiveLoadTimer.isRunning()) {
            progressiveLoadTimer.stop();
        }
        progressiveLoadTimer = null;
    }

    private void updateCardSizing() {
        ThumbnailSize selectedSize = (ThumbnailSize) thumbnailSizeCombo.getSelectedItem();
        if (selectedSize == null) {
            selectedSize = ThumbnailSize.MEDIUM;
        }
        int cardSize = selectedSize.cardSize();
        baseCardWidth = cardSize + 36;
        baseCardHeight = cardSize + 78;
        assetList.setFixedCellWidth(baseCardWidth);
        assetList.setFixedCellHeight(baseCardHeight);
    }

    private void adjustCardWidth(JScrollPane scrollPane) {
        if (baseCardWidth <= 0) return;
        int viewportWidth = scrollPane.getViewport().getWidth();
        if (viewportWidth <= 0) return;
        int columns = Math.max(1, viewportWidth / baseCardWidth);
        int adjustedWidth = viewportWidth / columns;
        if (adjustedWidth != assetList.getFixedCellWidth()) {
            assetList.setFixedCellWidth(adjustedWidth);
        }
    }

    private void buildAdvancedFiltersPanel() {
        boolean wasVisible = advancedFiltersPanel.isVisible();
        advancedFiltersPanel.removeAll();

        filtersGridPanel.removeAll();
        filtersGridPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel portraitPanel = new JPanel(new BorderLayout(4, 4));
        portraitPanel.add(new JLabel(get("portrait.label")), BorderLayout.NORTH);
        portraitPanel.add(portraitFilterCombo, BorderLayout.CENTER);
        // Row 1: animation, texture, portrait
        filtersGridPanel.add(labeledField(get("main.animationName"), animationFilterField));
        filtersGridPanel.add(labeledField(get("main.texturePath"), textureFilterField));
        filtersGridPanel.add(portraitPanel);
        filtersGridPanel.add(new JPanel()); // filler
        // Row 2: polygon range & size range
        filtersGridPanel.add(labeledField(get("main.minPolygons"), minPolygonsField));
        filtersGridPanel.add(labeledField(get("main.maxPolygons"), maxPolygonsField));
        filtersGridPanel.add(labeledField(get("main.minSizeKb"), minSizeKbField));
        filtersGridPanel.add(labeledField(get("main.maxSizeKb"), maxSizeKbField));
        // Wrap grid so it only takes ~half the width
        JPanel filtersWrapper = new JPanel(new GridLayout(1, 2));
        filtersWrapper.add(filtersGridPanel);
        filtersWrapper.add(new JPanel()); // right-side spacer

        // Tag buttons inside a scroll pane that wraps with viewport width
        tagButtonsPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        tagButtonsPanel.setToolTipText(get("main.matchesTags"));
        JScrollPane tagScroll = new JScrollPane(tagButtonsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        tagScroll.setBorder(null);
        // Make FlowLayout wrap to viewport width
        tagScroll.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int w = tagScroll.getViewport().getWidth();
                if (w > 0) {
                    tagButtonsPanel.setPreferredSize(new Dimension(w,
                            tagButtonsPanel.getPreferredSize().height));
                    tagButtonsPanel.revalidate();
                }
            }
        });

        advancedFiltersTabs.removeAll();
        advancedFiltersTabs.addTab(get("main.advancedFilters"), filtersWrapper);
        advancedFiltersTabs.addTab(get("main.tagFilter"), tagScroll);
        advancedFiltersPanel.add(advancedFiltersTabs, BorderLayout.CENTER);

        advancedFiltersPanel.setVisible(wasVisible);
        textureFilterField.setToolTipText(get("main.matchesTextures"));
        animationFilterField.setToolTipText(get("main.matchesAnimations"));
    }

    private JPanel labeledField(String label, JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private void wireFieldForFiltering(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFilter();
            }
        });
    }

    private Long parseKilobytesToBytes(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            long kb = Long.parseLong(trimmed);
            if (kb < 0) {
                return null;
            }
            return kb * 1024L;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseNonNegativeInt(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(trimmed);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Rebuilds the tag filter buttons from all scanned assets. */
    private void rebuildTagButtons() {
        // Collect all unique visible tags (readme + custom)
        java.util.TreeSet<String> allTags = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ModelAsset asset : allAssets) {
            allTags.addAll(getEffectiveTags(asset));
        }

        // Preserve existing states, remove stale ones
        tagStates.keySet().retainAll(allTags);
        for (String tag : allTags) {
            tagStates.putIfAbsent(tag, TagState.NEUTRAL);
        }

        tagButtonsPanel.removeAll();
        for (String tag : allTags) {
            JButton btn = new JButton(formatTagButtonLabel(tag, tagStates.get(tag)));
            btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 11f));
            btn.setFocusPainted(false);
            btn.setMargin(new java.awt.Insets(2, 6, 2, 6));
            styleTagButton(btn, tagStates.get(tag));
            btn.addActionListener(e -> {
                TagState next = tagStates.get(tag).next();
                tagStates.put(tag, next);
                btn.setText(formatTagButtonLabel(tag, next));
                styleTagButton(btn, next);
                applyFilter();
            });
            btn.setToolTipText(get("main.tagCycleHint"));
            tagButtonsPanel.add(btn);
        }
        tagButtonsPanel.revalidate();
        tagButtonsPanel.repaint();
    }

    private static String formatTagButtonLabel(String tag, TagState state) {
        return switch (state) {
            case INCLUDE -> "+ " + tag;
            case EXCLUDE -> "\u2212 " + tag;  // minus sign
            case NEUTRAL -> tag;
        };
    }

    private static void styleTagButton(JButton btn, TagState state) {
        switch (state) {
            case INCLUDE -> {
                btn.setBackground(new Color(200, 235, 200));
                btn.setForeground(new Color(30, 100, 30));
                btn.setOpaque(true);
            }
            case EXCLUDE -> {
                btn.setBackground(new Color(240, 200, 200));
                btn.setForeground(new Color(140, 30, 30));
                btn.setOpaque(true);
            }
            case NEUTRAL -> {
                btn.setBackground(null);
                btn.setForeground(null);
                btn.setOpaque(false);
            }
        }
    }

    /** Returns readme tags + custom tags, minus globally hidden tags. */
    private List<String> getEffectiveTags(ModelAsset asset) {
        java.util.Set<String> tags = new java.util.LinkedHashSet<>(asset.tags());
        tags.addAll(settings.getCustomTags(asset.path().toAbsolutePath().toString()));
        tags.removeIf(settings::isTagRemoved);
        return new java.util.ArrayList<>(tags);
    }

    private boolean matchesTagFilter(ModelAsset asset) {
        List<String> included = tagStates.entrySet().stream()
                .filter(e -> e.getValue() == TagState.INCLUDE)
                .map(java.util.Map.Entry::getKey)
                .toList();
        List<String> excluded = tagStates.entrySet().stream()
                .filter(e -> e.getValue() == TagState.EXCLUDE)
                .map(java.util.Map.Entry::getKey)
                .toList();

        List<String> visibleTags = getEffectiveTags(asset);

        // If any excluded tag matches, hide
        if (visibleTags.stream().anyMatch(tag -> excluded.stream().anyMatch(tag::equalsIgnoreCase))) {
            return false;
        }
        // If includes are active, model must have at least one included tag
        if (!included.isEmpty()) {
            return visibleTags.stream().anyMatch(tag -> included.stream().anyMatch(tag::equalsIgnoreCase));
        }
        return true;
    }

    private boolean polygonRangeMatches(ModelAsset asset, Integer minPolygons, Integer maxPolygons) {
        if (minPolygons == null && maxPolygons == null) {
            return true;
        }
        int polygonCount = asset.metadata().polygonCount();
        if (polygonCount < 0) {
            return false;
        }
        boolean meetsMin = minPolygons == null || polygonCount >= minPolygons;
        boolean meetsMax = maxPolygons == null || polygonCount <= maxPolygons;
        return meetsMin && meetsMax;
    }

    private String formatPolygonCount(int polygonCount) {
        if (polygonCount < 0) {
            return get("main.na");
        }
        return NumberFormat.getIntegerInstance().format(polygonCount);
    }

    private final class AssetCellRenderer extends DefaultListCellRenderer {
        private final CardPanel panel = new CardPanel();
        private final ShimmerPreviewLabel previewLabel = new ShimmerPreviewLabel();
        private final JLabel titleLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();
        private final JLabel tagsLabel = new JLabel();
        private final JLabel starLabel = new JLabel();

        private AssetCellRenderer() {
            panel.setLayout(new BorderLayout(0, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

            previewLabel.setOpaque(true);
            previewLabel.setBackground(new Color(24, 28, 34));
            previewLabel.setForeground(new Color(228, 233, 239));
            previewLabel.setFont(previewLabel.getFont().deriveFont(Font.BOLD, 14f));
            previewLabel.setPreferredSize(new Dimension(180, 180));
            previewLabel.setHorizontalAlignment(JLabel.CENTER);
            panel.add(previewLabel, BorderLayout.CENTER);

            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12.5f));
            titleLabel.setForeground(new Color(36, 42, 50));

            metaLabel.setFont(metaLabel.getFont().deriveFont(11f));
            metaLabel.setForeground(new Color(104, 112, 124));

            tagsLabel.setFont(tagsLabel.getFont().deriveFont(Font.PLAIN, 10f));
            tagsLabel.setForeground(new Color(80, 130, 180));

            starLabel.setFont(starLabel.getFont().deriveFont(Font.BOLD, 16f));
            starLabel.setForeground(new Color(255, 200, 50));

            JPanel bottomPanel = new JPanel(new BorderLayout(4, 0));
            bottomPanel.setOpaque(false);
            JPanel textPanel = new JPanel();
            textPanel.setLayout(new javax.swing.BoxLayout(textPanel, javax.swing.BoxLayout.Y_AXIS));
            textPanel.setOpaque(false);
            textPanel.add(titleLabel);
            textPanel.add(javax.swing.Box.createVerticalStrut(2));
            textPanel.add(metaLabel);
            textPanel.add(tagsLabel);
            bottomPanel.add(textPanel, BorderLayout.CENTER);
            bottomPanel.add(starLabel, BorderLayout.EAST);
            panel.add(bottomPanel, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            int cellWidth = list.getFixedCellWidth() > 0 ? list.getFixedCellWidth() : 216;
            int previewSize = Math.max(72, cellWidth - 56);
            previewLabel.setPreferredSize(new Dimension(previewSize, previewSize));
            panel.setPreferredSize(new Dimension(cellWidth, previewSize + 82));

            if (value instanceof ModelAsset asset) {
                boolean isFav = settings.isFavorite(asset.path().toAbsolutePath().toString());
                starLabel.setText(isFav ? "\u2605" : "");
                NumberFormat format = NumberFormat.getIntegerInstance();
                String displayName = asset.fileName();
                String mName = asset.metadata().modelName();
                if (!mName.isEmpty() && !mName.equalsIgnoreCase(
                        displayName.replaceFirst("\\.[^.]+$", ""))) {
                    displayName = displayName + " (" + mName + ")";
                }
                titleLabel.setText(displayName);
                if (asset.hasParseError()) {
                    metaLabel.setForeground(new Color(200, 100, 80));
                    metaLabel.setText(fmt("main.parseError", asset.parseError()));
                } else {
                    metaLabel.setForeground(new Color(104, 112, 124));
                    String polygonInfo = asset.metadata().hasKnownPolygonCount()
                            ? formatPolygonText(asset.metadata().polygonCount(), format)
                            : get("main.polyNA");
                    metaLabel.setText(formatFileSize(asset.fileSizeBytes()) + " | " + polygonInfo);
                }

                // Tags
                List<String> visibleTags = getEffectiveTags(asset);
                if (!visibleTags.isEmpty()) {
                    tagsLabel.setText(String.join(", ", visibleTags));
                    tagsLabel.setVisible(true);
                } else {
                    tagsLabel.setText("");
                    tagsLabel.setVisible(false);
                }

                // HD models are unsupported — show a static badge instead of a thumbnail.
                if (asset.metadata().isHd()) {
                    previewLabel.setUnsupported(previewSize);
                    previewLabel.setText(get("main.hdUnsupportedBadge"));
                } else {
                    // Show thumbnail if available, otherwise shimmer
                    BufferedImage thumb = thumbnailRenderer != null
                            ? thumbnailRenderer.getThumbnail(asset.path()) : null;
                    if (thumb != null) {
                        previewLabel.setThumbnail(thumb, previewSize);
                        previewLabel.setText("");
                    } else {
                        previewLabel.setThumbnail(null, previewSize);
                        previewLabel.setText(asset.fileExtension().toUpperCase(Locale.ROOT));
                    }
                }

                ModelMetadata meta = asset.metadata();
                NumberFormat nfmt = NumberFormat.getIntegerInstance();
                String tooltipName = asset.fileName();
                String mn = meta.modelName();
                if (!mn.isEmpty()) tooltipName += " (" + mn + ")";
                String tooltip;
                if (asset.hasParseError()) {
                    tooltip = "<html><b>" + tooltipName + "</b><br>"
                            + "<font color='#cc6644'>" + fmt("main.tooltip.parseError", escapeHtml(asset.parseError())) + "</font><br>"
                            + fmt("main.tooltip.size", formatFileSize(asset.fileSizeBytes())) + "<br>"
                            + "<font color='gray'>" + asset.path() + "</font></html>";
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("<html><b>").append(tooltipName).append("</b><br>")
                            .append(fmt("main.tooltip.polygons", meta.hasKnownPolygonCount() ? nfmt.format(meta.polygonCount()) : get("main.na"))).append("<br>")
                            .append(fmt("main.tooltip.vertices", nfmt.format(meta.vertexCount()))).append("<br>")
                            .append(fmt("main.tooltip.bones", nfmt.format(meta.boneCount()))).append("<br>")
                            .append(fmt("main.tooltip.sequences", nfmt.format(meta.sequenceCount()))).append("<br>")
                            .append(fmt("main.tooltip.size", formatFileSize(asset.fileSizeBytes()))).append("<br>");
                    if (!visibleTags.isEmpty()) {
                        sb.append("<font color='#5082b4'>")
                                .append(fmt("main.tooltip.tags", String.join(", ", visibleTags)))
                                .append("</font><br>");
                    }
                    sb.append("<font color='gray'>").append(asset.path()).append("</font></html>");
                    tooltip = sb.toString();
                }
                panel.setToolTipText(tooltip);
            }
            panel.setCardStyle(isSelected);
            return panel;
        }

        private String formatPolygonText(int polygonCount, NumberFormat format) {
            return format.format(polygonCount) + " " + get("main.poly");
        }

        private String formatFileSize(long bytes) {
            if (bytes >= 1_000_000) {
                return String.format("%.1f MB", bytes / 1_000_000.0);
            } else if (bytes >= 1_000) {
                return String.format("%.1f KB", bytes / 1_000.0);
            } else {
                return bytes + " B";
            }
        }

        private static String escapeHtml(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    /**
     * Custom JLabel that shows a thumbnail image (scaled to fit) or an animated
     * shimmer gradient when no thumbnail is available yet.
     */
    private static final class ShimmerPreviewLabel extends JLabel {
        private static final Color SHIMMER_BASE = new Color(44, 49, 56);
        private static final Color SHIMMER_HIGHLIGHT = new Color(64, 69, 76);
        private static final Color LOADING_TEXT_COLOR = new Color(140, 150, 160);
        private static final Color SPINNER_COLOR = new Color(100, 140, 200);
        private static final Color PREVIEW_FRAME = new Color(255, 255, 255, 24);
        private static final Color UNSUPPORTED_BG = new Color(36, 38, 44);
        private static final int IMAGE_OVERSCAN = 2;
        private BufferedImage scaledThumb;
        private BufferedImage lastSource;
        private int lastSize;
        private boolean unsupported;

        void setThumbnail(BufferedImage thumb, int displaySize) {
            unsupported = false;
            if (thumb != null) {
                // Only rescale if source or size changed
                if (thumb != lastSource || displaySize != lastSize) {
                    lastSource = thumb;
                    lastSize = displaySize;
                    scaledThumb = scaleThumbnail(thumb, displaySize);
                }
            } else {
                scaledThumb = null;
                lastSource = null;
                lastSize = displaySize;
            }
            repaint();
        }

        /** Static "unsupported" placeholder — no shimmer, no spinner, just a flat
         *  background. The cell's {@link JLabel#setText(String)} content is drawn on top. */
        void setUnsupported(int displaySize) {
            unsupported = true;
            scaledThumb = null;
            lastSource = null;
            lastSize = displaySize;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, w, h, 18, 18);

            if (scaledThumb == null && unsupported) {
                // Static placeholder for HD/unsupported models — no animation.
                Shape clip = g2.getClip();
                g2.clip(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
                g2.setColor(UNSUPPORTED_BG);
                g2.fillRect(0, 0, w, h);
                g2.setClip(clip);
            } else if (scaledThumb == null) {
                // Draw shimmer animation
                float phase = (System.currentTimeMillis() % 1500) / 1500f;
                int bandWidth = w / 3;
                int bandX = (int) ((phase * (w + bandWidth)) - bandWidth);

                Shape clip = g2.getClip();
                g2.clip(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
                g2.setPaint(new GradientPaint(
                        bandX, 0, SHIMMER_BASE,
                        bandX + bandWidth / 2, 0, SHIMMER_HIGHLIGHT));
                g2.fillRect(bandX, 0, bandWidth / 2, h);
                g2.setPaint(new GradientPaint(
                        bandX + bandWidth / 2, 0, SHIMMER_HIGHLIGHT,
                        bandX + bandWidth, 0, SHIMMER_BASE));
                g2.fillRect(bandX + bandWidth / 2, 0, bandWidth / 2, h);
                g2.setClip(clip);

                // Draw spinning arc
                int spinnerSize = Math.min(w, h) / 4;
                int sx = (w - spinnerSize) / 2;
                int sy = (h - spinnerSize) / 2 - 8;
                float spinAngle = (System.currentTimeMillis() % 1000) / 1000f * 360f;
                g2.setColor(SPINNER_COLOR);
                g2.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                g2.drawArc(sx, sy, spinnerSize, spinnerSize, (int) spinAngle, 270);

                // "Loading..." text below spinner
                g2.setFont(getFont().deriveFont(Font.PLAIN, 10f));
                g2.setColor(LOADING_TEXT_COLOR);
                java.awt.FontMetrics fm = g2.getFontMetrics();
                String loadText = Messages.get("main.loading");
                int tx = (w - fm.stringWidth(loadText)) / 2;
                int ty = sy + spinnerSize + fm.getHeight() + 4;
                g2.drawString(loadText, tx, ty);
            } else {
                Shape clip = g2.getClip();
                g2.clip(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
                g2.drawImage(scaledThumb,
                        -IMAGE_OVERSCAN, -IMAGE_OVERSCAN,
                        w + IMAGE_OVERSCAN * 2, h + IMAGE_OVERSCAN * 2,
                        null);
                g2.setClip(clip);
            }
            String text = getText();
            if (text != null && !text.isBlank()) {
                g2.setFont(getFont());
                g2.setColor(getForeground());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(text)) / 2;
                int ty = h - 14;
                g2.drawString(text, tx, ty);
            }
            g2.setColor(PREVIEW_FRAME);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 18, 18);
            g2.dispose();
        }

        private static BufferedImage scaleThumbnail(BufferedImage thumb, int displaySize) {
            BufferedImage scaled = new BufferedImage(displaySize, displaySize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(thumb, 0, 0, displaySize, displaySize, null);
            g2.dispose();
            return scaled;
        }
    }

    private static final class CardPanel extends JPanel {
        private static final int CARD_MARGIN = 6;
        private Color fill = new Color(249, 250, 252);
        private Color stroke = new Color(220, 225, 232);

        private CardPanel() {
            setOpaque(false);
        }

        void setCardStyle(boolean selected) {
            if (selected) {
                fill = new Color(231, 240, 255);
                stroke = new Color(94, 146, 235);
            } else {
                fill = new Color(249, 250, 252);
                stroke = new Color(220, 225, 232);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(CARD_MARGIN, CARD_MARGIN,
                    getWidth() - CARD_MARGIN * 2, getHeight() - CARD_MARGIN * 2, 22, 22);
            g2.setColor(stroke);
            g2.drawRoundRect(CARD_MARGIN, CARD_MARGIN,
                    getWidth() - CARD_MARGIN * 2 - 1, getHeight() - CARD_MARGIN * 2 - 1, 22, 22);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Shows an error dialog with selectable/copyable message text. */
    static void showErrorDialog(Component parent, String message, String title) {
        javax.swing.JTextArea textArea = new javax.swing.JTextArea(message);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBackground(javax.swing.UIManager.getColor("Panel.background"));
        textArea.setFont(javax.swing.UIManager.getFont("Label.font"));
        textArea.setBorder(null);
        textArea.setColumns(40);
        // Let the text area size itself, then cap height
        textArea.setSize(textArea.getPreferredSize());
        int rows = Math.min(textArea.getLineCount(), 20);
        textArea.setRows(rows);
        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(450,
                Math.min(textArea.getPreferredSize().height + 10, 400)));
        JOptionPane.showMessageDialog(parent, scroll, title, JOptionPane.ERROR_MESSAGE);
    }
}
