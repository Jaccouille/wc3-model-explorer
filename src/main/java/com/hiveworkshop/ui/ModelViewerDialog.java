package com.hiveworkshop.ui;

import static com.hiveworkshop.i18n.Messages.get;
import static com.hiveworkshop.i18n.Messages.fmt;

import com.hiveworkshop.model.*;
import com.hiveworkshop.parser.AppSettings;
import com.hiveworkshop.parser.GameDataSource;
import com.hiveworkshop.parser.ReterasModelParser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import java.io.File;

/**
 * Model viewer dialog with a split-pane layout:
 *   Left  – OpenGL 3D preview canvas
 *   Right – tabbed panel (Animation, Info, Textures, Materials, Geosets, Nodes)
 *
 * Layout reproduces the structure of War3AdvancedModelViewer's ModelDetailDialog.
 *
 * <p>Extends {@link JFrame} rather than {@code JDialog} so the OS supplies native
 * minimize and maximize window controls (a {@code JDialog} only offers a close button).
 */
public final class ModelViewerDialog extends JFrame {
    private static final int VIEW_W = 850;
    private static final int DIAG_W = 550;

    private final GlPreviewCanvas previewCanvas;
    private final ReterasParsedModel parsedModel;
    private final ModelAsset asset;
    private final Path scanRoot;
    private final AppSettings settings; // shared with MainWindow so saves don't clobber
    private Throwable canvasError; // non-null if GL canvas creation failed
    private Timer scrubberSyncTimer;

    // Grid controls kept in sync (overlay toggle button + animation-tab checkbox)
    private JToggleButton gridOverlayBtn;
    private JCheckBox gridCheckbox;
    private boolean syncingGrid;
    private JComboBox<String> shadingCombo; // referenced by the reset-to-defaults action

    public ModelViewerDialog(JFrame owner, ModelAsset asset, Path scanRoot, AppSettings settings) {
        super(fmt("viewer.title", asset.fileName())
                + (asset.metadata().modelName().isEmpty() ? ""
                   : " (" + asset.metadata().modelName() + ")"));
        // Inherit the owner's taskbar/title-bar icon (JFrame has no owner concept).
        if (owner != null && !owner.getIconImages().isEmpty()) {
            setIconImages(owner.getIconImages());
        }
        this.settings = settings;
        this.asset = asset;
        this.scanRoot = scanRoot;
        setSize(new Dimension(VIEW_W + DIAG_W, 700));
        setMinimumSize(new Dimension(800, 600));
        setLayout(new BorderLayout());

        ReterasModelParser.invalidate(asset.path()); // ensure fresh parse with latest extractor
        parsedModel = ReterasModelParser.parse(asset.path());

        // ── GL canvas ────────────────────────────────────────────────────
        GlPreviewCanvas createdCanvas;
        try {
            createdCanvas = new GlPreviewCanvas(
                    parsedModel.mesh(), parsedModel.animData(),
                    parsedModel.texData(), parsedModel.collisionShapes(),
                    parsedModel.ribbonEmitters(), parsedModel.particleEmitters2(),
                    parsedModel.materials(),
                    asset.path().getParent(), scanRoot);
        } catch (Throwable t) {
            System.err.println("[ModelViewer] GlPreviewCanvas failed: " + t);
            t.printStackTrace();
            createdCanvas = null;
            canvasError = t;
        }
        if (createdCanvas != null) {
            createdCanvas.setInitialCamera(settings.cameraYaw(), settings.cameraPitch());
        }
        this.previewCanvas = createdCanvas;

        // ── Safe close (background GL-Stopper thread) ────────────────────
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (scrubberSyncTimer != null) scrubberSyncTimer.stop();
                if (previewCanvas == null) { dispose(); return; }
                new Thread(() -> {
                    previewCanvas.stopRenderThread();
                    SwingUtilities.invokeLater(() -> dispose());
                }, "GL-Stopper").start();
            }
        });

        // ── Build layout ─────────────────────────────────────────────────
        JPanel leftPanel = buildLeftPanel();
        JTabbedPane rightTabs = buildRightTabs();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightTabs);
        splitPane.setDividerLocation(VIEW_W);
        splitPane.setResizeWeight(1.0); // extra space goes to the 3D view
        splitPane.setOneTouchExpandable(true);
        add(splitPane, BorderLayout.CENTER);

        // Auto-select first sequence
        if (previewCanvas != null) {
            // Apply background color from settings
            previewCanvas.setBackgroundColor(settings.bgColor());
            if (parsedModel.animData().hasAnimation()) {
                if (settings.viewerBool("noSequence", false)) {
                    previewCanvas.setNoSequence(); // restore last "None" (default pose) state
                } else {
                    previewCanvas.setSequence(0);
                    previewCanvas.setPlaying(settings.viewerBool("playing", true));
                    previewCanvas.reframeToSequence(parsedModel.animData().sequences().get(0));
                }
            }
        }
    }

    // ── Left panel: GL canvas with fallback ──────────────────────────────

    private JPanel buildLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setMinimumSize(new Dimension(400, 300));
        if (previewCanvas != null) {
            // JLayeredPane with manual sizing so GL canvas + overlays stack correctly
            JLabel statsLabel = buildStatsHud();
            JComboBox<String> shadingCombo = buildShadingCombo();
            JButton screenshotBtn = buildScreenshotButton();
            JToggleButton gridBtn = buildGridOverlayButton();
            JButton recenterBtn = buildRecenterButton();

            // JLayeredPane with doLayout() override to reliably size children
            JLayeredPane layered = new JLayeredPane() {
                @Override
                public void doLayout() {
                    int w = getWidth(), h = getHeight();
                    previewCanvas.setBounds(0, 0, w, h);
                    statsLabel.setBounds(8, 8, 150, 95);
                    // Uniform width for the shading select + overlay buttons, right-aligned
                    int cw = Math.max(
                            Math.max(shadingCombo.getPreferredSize().width, gridBtn.getPreferredSize().width),
                            Math.max(recenterBtn.getPreferredSize().width, screenshotBtn.getPreferredSize().width));
                    int x = w - cw - 8;
                    int ch = shadingCombo.getPreferredSize().height;
                    shadingCombo.setBounds(x, 8, cw, ch);
                    // Grid + recenter buttons stacked just below the shading combo
                    int gh = gridBtn.getPreferredSize().height;
                    int gridY = 8 + ch + 6;
                    gridBtn.setBounds(x, gridY, cw, gh);
                    int rh = recenterBtn.getPreferredSize().height;
                    recenterBtn.setBounds(x, gridY + gh + 4, cw, rh);
                    int sh = screenshotBtn.getPreferredSize().height;
                    screenshotBtn.setBounds(x, h - sh - 8, cw, sh);
                }
            };

            layered.add(previewCanvas, JLayeredPane.DEFAULT_LAYER);
            layered.add(statsLabel, JLayeredPane.PALETTE_LAYER);
            layered.add(shadingCombo, JLayeredPane.PALETTE_LAYER);
            layered.add(gridBtn, JLayeredPane.PALETTE_LAYER);
            layered.add(recenterBtn, JLayeredPane.PALETTE_LAYER);
            layered.add(screenshotBtn, JLayeredPane.PALETTE_LAYER);

            layered.setPreferredSize(new Dimension(VIEW_W, 600));
            panel.add(layered, BorderLayout.CENTER);
        } else {
            String msg = canvasError != null
                    ? "<html><center>" + fmt("viewer.glFailed", asset.fileName()) + "<br>"
                      + fmt("viewer.glFailedDetail", escapeHtml(canvasError.toString()))
                      + "</center></html>"
                    : "<html><center>" + fmt("viewer.glUnavailable", asset.fileName()) + "</center></html>";
            JLabel fallback = new JLabel(msg, JLabel.CENTER);
            fallback.setForeground(new Color(200, 80, 80));
            fallback.setBorder(new EmptyBorder(12, 12, 12, 12));
            if (canvasError != null) {
                fallback.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                fallback.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                        if (e.getClickCount() == 2) showErrorStackTrace(canvasError);
                    }
                });
            }
            panel.add(fallback, BorderLayout.CENTER);
        }
        return panel;
    }

    private JLabel buildStatsHud() {
        NumberFormat nfmt = NumberFormat.getIntegerInstance();
        ModelMesh mesh = parsedModel.mesh();
        ModelAnimData animData = parsedModel.animData();

        int verts = mesh.vertices().length / 3;
        int tris = mesh.indices().length / 3;
        int bones = animData.bones().length;
        int geosets = (int) animData.geosets().stream().filter(g -> g.vertexCount() > 0).count();
        int seqs = animData.sequences().size();

        String html = "<html><body style='font-family:monospace; font-size:10px; color:#ccddee;'>"
                + fmt("viewer.geosets", geosets) + "<br>"
                + fmt("viewer.vertices", nfmt.format(verts)) + "<br>"
                + fmt("viewer.triangles", nfmt.format(tris)) + "<br>"
                + fmt("viewer.bones", bones) + "<br>"
                + fmt("viewer.sequences", seqs)
                + "</body></html>";

        JLabel label = new JLabel(html);
        label.setOpaque(true);
        label.setBackground(new Color(0, 0, 0, 140));
        label.setBorder(new EmptyBorder(6, 10, 6, 10));
        label.setBounds(8, 8, 150, 95);
        return label;
    }

    private JComboBox<String> buildShadingCombo() {
        JComboBox<String> combo = new JComboBox<>(new String[]{
            get("viewer.solid"), get("viewer.textured"), get("viewer.lit"), get("viewer.normals"),
            get("viewer.geosetsMode"), get("viewer.boneCount"), get("viewer.wireframe")
        });
        combo.setOpaque(false);
        combo.addActionListener(e -> {
            applyShadingMode(combo.getSelectedIndex());
            persist("shadingIndex", combo.getSelectedIndex());
        });
        int persisted = settings.viewerInt("shadingIndex", 1);
        combo.setSelectedIndex(persisted);
        applyShadingMode(persisted); // ensure canvas matches even when persisted == current index
        this.shadingCombo = combo;
        return combo;
    }

    /** Applies the shading combo selection (index 6 == wireframe) to the canvas. */
    private void applyShadingMode(int idx) {
        if (previewCanvas == null) return;
        if (idx == 6) { // Wireframe
            previewCanvas.setShadingMode(GlPreviewCanvas.ShadingMode.SOLID);
            previewCanvas.setWireframe(true);
        } else {
            previewCanvas.setWireframe(false);
            GlPreviewCanvas.ShadingMode mode = switch (idx) {
                case 0  -> GlPreviewCanvas.ShadingMode.SOLID;
                case 2  -> GlPreviewCanvas.ShadingMode.LIT;
                case 3  -> GlPreviewCanvas.ShadingMode.NORMALS;
                case 4  -> GlPreviewCanvas.ShadingMode.GEOSET_COLORS;
                case 5  -> GlPreviewCanvas.ShadingMode.BONE_COUNT;
                default -> GlPreviewCanvas.ShadingMode.TEXTURED;
            };
            previewCanvas.setShadingMode(mode);
        }
    }

    private JButton buildScreenshotButton() {
        JButton btn = new JButton(get("viewer.screenshot"));
        btn.setFocusable(false);
        btn.setToolTipText(get("viewer.screenshotTooltip"));
        btn.addActionListener(e -> {
            if (previewCanvas == null) return;
            previewCanvas.requestScreenshot().thenAccept(img -> {
                SwingUtilities.invokeLater(() -> {
                    JFileChooser fc = new JFileChooser();
                    if (MainWindow.lastChooserDir != null) fc.setCurrentDirectory(MainWindow.lastChooserDir);
                    String baseName = asset.fileName().replaceFirst("\\.[^.]+$", "");
                    fc.setSelectedFile(new File(baseName + ".png"));
                    fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(get("viewer.pngImage"), "png"));
                    if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                        MainWindow.lastChooserDir = fc.getCurrentDirectory();
                        File file = fc.getSelectedFile();
                        if (!file.getName().toLowerCase().endsWith(".png")) {
                            file = new File(file.getAbsolutePath() + ".png");
                        }
                        try {
                            ImageIO.write(img, "PNG", file);
                        } catch (Exception ex) {
                            MainWindow.showErrorDialog(this, fmt("viewer.failedScreenshot", ex.getMessage()), get("main.error"));
                        }
                    }
                });
            });
        });
        return btn;
    }

    private JToggleButton buildGridOverlayButton() {
        JToggleButton btn = new JToggleButton(get("viewer.showGrid"), settings.viewerBool("showGrid", true));
        btn.setFocusable(false);
        btn.setToolTipText(get("viewer.gridToggleTooltip"));
        btn.addActionListener(e -> setGridVisible(btn.isSelected()));
        this.gridOverlayBtn = btn;
        return btn;
    }

    private JButton buildRecenterButton() {
        JButton btn = new JButton(get("viewer.recenter"));
        btn.setFocusable(false);
        btn.setToolTipText(get("viewer.recenterTooltip"));
        btn.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.reframeToSequence(previewCanvas.getCurrentSequence());
        });
        return btn;
    }

    /** Applies grid visibility to the canvas and keeps both grid controls in sync. */
    private void setGridVisible(boolean on) {
        if (syncingGrid) return;
        syncingGrid = true;
        if (previewCanvas != null) previewCanvas.setShowGrid(on);
        if (gridCheckbox != null && gridCheckbox.isSelected() != on) gridCheckbox.setSelected(on);
        if (gridOverlayBtn != null && gridOverlayBtn.isSelected() != on) gridOverlayBtn.setSelected(on);
        syncingGrid = false;
        persist("showGrid", on);
    }

    // Persist a single viewer preference and flush to disk (used on every control change).
    private void persist(String key, boolean value) { settings.setViewerPref(key, value); settings.save(); }
    private void persist(String key, int value)     { settings.setViewerPref(key, value); settings.save(); }

    /** Sets a checkbox to {@code target} via doClick so its ActionListener fires (setSelected does not). */
    private static void setChecked(JCheckBox box, boolean target) {
        if (box.isEnabled() && box.isSelected() != target) box.doClick();
    }

    // ── Right panel: tabbed pane ─────────────────────────────────────────

    private JTabbedPane buildRightTabs() {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setMinimumSize(new Dimension(280, 0));
        tabs.setPreferredSize(new Dimension(DIAG_W, 0));

        tabs.addTab(get("viewer.tab.animation"), buildAnimationTab());
        tabs.addTab(get("viewer.tab.info"), buildInfoTab());
        tabs.addTab(get("viewer.tab.textures"), buildTexturesTab());
        tabs.addTab(get("viewer.tab.materials"), buildMaterialsTab());
        tabs.addTab(get("viewer.tab.geosets"), buildGeosetsTab());
        tabs.addTab(get("viewer.tab.uvmap"), buildUVMapTab());
        if (parsedModel.animData().bones().length > 0) {
            tabs.addTab(get("viewer.tab.nodes"), buildNodesTab());
        }

        // Clear highlights when switching away from contextual tabs
        tabs.addChangeListener(e -> {
            if (previewCanvas == null) return;
            int idx = tabs.getSelectedIndex();
            String title = idx >= 0 ? tabs.getTitleAt(idx) : "";
            if (!get("viewer.tab.materials").equals(title)) previewCanvas.setHighlightedGeosetIndices(null);
            if (!get("viewer.tab.geosets").equals(title) && !get("viewer.tab.uvmap").equals(title)) {
                previewCanvas.setHighlightedGeosetIdx(-1);
                previewCanvas.setHighlightWireframe(false);
            }
            if (!get("viewer.tab.nodes").equals(title))     previewCanvas.setHighlightedBoneId(-1);
        });

        return tabs;
    }

    // ── Animation tab ────────────────────────────────────────────────────

    private JPanel buildAnimationTab() {
        ModelAnimData animData = parsedModel.animData();
        List<SequenceInfo> sequences = animData.sequences();

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Sequence selector
        JPanel seqRow = flowRow(get("viewer.animation"));
        JComboBox<String> seqCombo = buildSequenceCombo(sequences);
        seqCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        seqRow.add(seqCombo);
        panel.add(seqRow);
        panel.add(Box.createVerticalStrut(8));

        // Play / Pause + Loop
        JPanel playRow = flowRow();
        JButton playPauseBtn = new JButton(settings.viewerBool("playing", true) ? get("viewer.stop") : get("viewer.play"));
        playPauseBtn.setEnabled(animData.hasAnimation());
        playPauseBtn.addActionListener(e -> {
            if (previewCanvas == null) return;
            boolean nowPlaying = !previewCanvas.isPlaying();
            previewCanvas.setPlaying(nowPlaying);
            playPauseBtn.setText(nowPlaying ? get("viewer.stop") : get("viewer.play"));
            persist("playing", nowPlaying);
        });
        JCheckBox loopCheckbox = new JCheckBox(get("viewer.loop"), settings.viewerBool("loop", true));
        loopCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setLooping(loopCheckbox.isSelected());
            persist("loop", loopCheckbox.isSelected());
        });
        if (previewCanvas != null) previewCanvas.setLooping(loopCheckbox.isSelected());
        // Reset button to "Play" when a non-looping animation finishes
        previewCanvas.setOnAnimationFinished(() -> {
            playPauseBtn.setText(get("viewer.play"));
        });
        playRow.add(playPauseBtn);
        playRow.add(loopCheckbox);
        panel.add(playRow);
        panel.add(Box.createVerticalStrut(8));

        // Timeline scrubber (full-width)
        JLabel timeLabel = new JLabel("0 ms");
        timeLabel.setPreferredSize(new Dimension(70, 20));
        SequenceInfo initSeq = (!sequences.isEmpty() && previewCanvas != null)
                ? previewCanvas.getCurrentSequence() : null;
        int initDuration = initSeq != null ? (int)(initSeq.end() - initSeq.start()) : 1000;
        JSlider timeSlider = new JSlider(0, Math.max(1, initDuration), 0);
        timeSlider.setEnabled(animData.hasAnimation());
        final boolean[] scrubbing = {false};
        final boolean[] suppressTimeUpdate = {false};
        timeSlider.addChangeListener(e -> {
            if (suppressTimeUpdate[0]) return;
            if (previewCanvas == null) return;
            SequenceInfo seq = previewCanvas.getCurrentSequence();
            if (seq == null) return;
            long t = seq.start() + timeSlider.getValue();
            timeLabel.setText(timeSlider.getValue() + " ms");
            if (timeSlider.getValueIsAdjusting()) {
                if (!scrubbing[0]) {
                    scrubbing[0] = true;
                    previewCanvas.setPlaying(false);
                    playPauseBtn.setText(get("viewer.play"));
                }
                previewCanvas.setAnimTimeMs(t);
            } else if (scrubbing[0]) {
                scrubbing[0] = false;
                previewCanvas.setAnimTimeMs(t);
            }
        });
        JPanel timeRow = new JPanel(new BorderLayout(6, 0));
        timeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        timeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        timeRow.add(new JLabel(get("viewer.time")), BorderLayout.WEST);
        timeRow.add(timeSlider, BorderLayout.CENTER);
        timeRow.add(timeLabel, BorderLayout.EAST);
        panel.add(timeRow);
        panel.add(Box.createVerticalStrut(4));

        // Timer to sync scrubber position with playback
        scrubberSyncTimer = new Timer(33, e -> {
            if (previewCanvas == null || scrubbing[0]) return;
            SequenceInfo seq = previewCanvas.getCurrentSequence();
            if (seq == null) return;
            int pos = (int)(previewCanvas.getAnimTimeMs() - seq.start());
            suppressTimeUpdate[0] = true;
            timeSlider.setValue(Math.max(0, Math.min(timeSlider.getMaximum(), pos)));
            timeLabel.setText(Math.max(0, pos) + " ms");
            suppressTimeUpdate[0] = false;
        });
        scrubberSyncTimer.start();

        // Speed slider + editable field (0.01x – 3.00x, two-digit precision)
        JPanel speedRow = flowRow(get("viewer.speed"));
        JTextField speedField = new JTextField(4);
        speedField.setHorizontalAlignment(JTextField.RIGHT);
        speedField.setMaximumSize(new Dimension(56, 24));
        speedField.setPreferredSize(new Dimension(56, 24));
        JSlider speedSlider = new JSlider(1, 300, settings.viewerInt("speedPct", 100));
        speedSlider.setPreferredSize(new Dimension(160, 24));
        speedSlider.setEnabled(animData.hasAnimation());
        speedField.setEnabled(animData.hasAnimation());
        // Slider drives field/canvas; setText does not re-trigger the field, so no feedback loop.
        speedSlider.addChangeListener(e -> {
            float spd = speedSlider.getValue() / 100f;
            speedField.setText(String.format("%.2f", spd));
            if (previewCanvas != null) previewCanvas.setSpeed(spd);
            if (!speedSlider.getValueIsAdjusting()) persist("speedPct", speedSlider.getValue());
        });
        // Field commits on Enter or focus loss; clamps to 0.01–3.00 and snaps the slider.
        Runnable applyField = () -> {
            try {
                float spd = Float.parseFloat(speedField.getText().trim().replace(',', '.'));
                int pct = Math.max(1, Math.min(300, Math.round(spd * 100)));
                speedSlider.setValue(pct);
                speedField.setText(String.format("%.2f", pct / 100f));
            } catch (NumberFormatException ex) {
                speedField.setText(String.format("%.2f", speedSlider.getValue() / 100f));
            }
        };
        speedField.addActionListener(e -> applyField.run());
        speedField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { applyField.run(); }
        });
        speedField.setText(String.format("%.2f", speedSlider.getValue() / 100f));
        if (previewCanvas != null) previewCanvas.setSpeed(speedSlider.getValue() / 100f);
        speedRow.add(speedSlider);
        speedRow.add(speedField);
        panel.add(speedRow);
        panel.add(Box.createVerticalStrut(12));

        // Separator
        panel.add(new JSeparator(SwingConstants.HORIZONTAL));
        panel.add(Box.createVerticalStrut(8));

        // Extent overlay
        JCheckBox extentCheckbox = new JCheckBox(get("viewer.showExtent"), settings.viewerBool("showExtent", false));
        extentCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        extentCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowExtent(extentCheckbox.isSelected());
            persist("showExtent", extentCheckbox.isSelected());
        });
        if (previewCanvas != null) previewCanvas.setShowExtent(extentCheckbox.isSelected());
        panel.add(extentCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Node overlays
        JCheckBox bonesCheckbox = new JCheckBox(get("viewer.bonesOverlay"), settings.viewerBool("showBones", false));
        bonesCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        bonesCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowBones(bonesCheckbox.isSelected());
            persist("showBones", bonesCheckbox.isSelected());
        });
        if (previewCanvas != null) previewCanvas.setShowBones(bonesCheckbox.isSelected());
        panel.add(bonesCheckbox);
        panel.add(Box.createVerticalStrut(4));

        JCheckBox helpersCheckbox = new JCheckBox(get("viewer.helpers"), settings.viewerBool("showHelpers", false));
        helpersCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        helpersCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowHelpers(helpersCheckbox.isSelected());
            persist("showHelpers", helpersCheckbox.isSelected());
        });
        if (previewCanvas != null) previewCanvas.setShowHelpers(helpersCheckbox.isSelected());
        panel.add(helpersCheckbox);
        panel.add(Box.createVerticalStrut(4));

        JCheckBox attachmentsCheckbox = new JCheckBox(get("viewer.attachments"), settings.viewerBool("showAttachments", false));
        attachmentsCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        attachmentsCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowAttachments(attachmentsCheckbox.isSelected());
            persist("showAttachments", attachmentsCheckbox.isSelected());
        });
        if (previewCanvas != null) previewCanvas.setShowAttachments(attachmentsCheckbox.isSelected());
        panel.add(attachmentsCheckbox);
        panel.add(Box.createVerticalStrut(4));

        boolean hasRibbons = parsedModel.ribbonEmitters().length > 0;
        JCheckBox ribbonsCheckbox = new JCheckBox(get("viewer.ribbonEmitters"),
                hasRibbons && settings.viewerBool("showRibbons", false));
        ribbonsCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        ribbonsCheckbox.setEnabled(hasRibbons);
        ribbonsCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowRibbonEmitters(ribbonsCheckbox.isSelected());
            persist("showRibbons", ribbonsCheckbox.isSelected());
        });
        if (previewCanvas != null) previewCanvas.setShowRibbonEmitters(ribbonsCheckbox.isSelected());
        panel.add(ribbonsCheckbox);
        panel.add(Box.createVerticalStrut(4));

        boolean hasParticles = parsedModel.particleEmitters2().length > 0;
        JCheckBox particlesCheckbox = new JCheckBox(get("viewer.particleEmitters"),
                hasParticles && settings.viewerBool("showParticles", false));
        particlesCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        particlesCheckbox.setEnabled(hasParticles);
        particlesCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowParticleEmitters(particlesCheckbox.isSelected());
            persist("showParticles", particlesCheckbox.isSelected());
        });
        if (previewCanvas != null) previewCanvas.setShowParticleEmitters(particlesCheckbox.isSelected());
        panel.add(particlesCheckbox);
        panel.add(Box.createVerticalStrut(4));

        JCheckBox nodeNamesCheckbox = new JCheckBox(get("viewer.nodeNames"), settings.viewerBool("showNodeNames", false));
        nodeNamesCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        nodeNamesCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowNodeNames(nodeNamesCheckbox.isSelected());
            persist("showNodeNames", nodeNamesCheckbox.isSelected());
        });
        if (previewCanvas != null) previewCanvas.setShowNodeNames(nodeNamesCheckbox.isSelected());
        panel.add(nodeNamesCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Node size slider
        JPanel nodeSizeRow = flowRow(get("viewer.nodeSize"));
        JLabel nodeSizeLabel = new JLabel("3.0");
        nodeSizeLabel.setPreferredSize(new Dimension(30, 20));
        JSlider nodeSizeSlider = new JSlider(5, 200, settings.viewerInt("nodeSize", 30)); // 0.5 to 20.0 (÷10)
        nodeSizeSlider.setPreferredSize(new Dimension(120, 24));
        nodeSizeSlider.addChangeListener(e -> {
            float sz = nodeSizeSlider.getValue() / 10f;
            nodeSizeLabel.setText(String.format("%.1f", sz));
            if (previewCanvas != null) previewCanvas.setNodeSize(sz);
            if (!nodeSizeSlider.getValueIsAdjusting()) persist("nodeSize", nodeSizeSlider.getValue());
        });
        nodeSizeLabel.setText(String.format("%.1f", nodeSizeSlider.getValue() / 10f));
        if (previewCanvas != null) previewCanvas.setNodeSize(nodeSizeSlider.getValue() / 10f);
        nodeSizeRow.add(nodeSizeSlider);
        nodeSizeRow.add(nodeSizeLabel);
        panel.add(nodeSizeRow);
        panel.add(Box.createVerticalStrut(6));

        // Collision shapes overlay
        boolean hasCollision = parsedModel.collisionShapes().length > 0;
        JCheckBox collisionCheckbox = new JCheckBox(get("viewer.collisionShapes"),
                hasCollision && settings.viewerBool("showCollision", false));
        collisionCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        collisionCheckbox.setEnabled(hasCollision);
        collisionCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowCollision(collisionCheckbox.isSelected());
            persist("showCollision", collisionCheckbox.isSelected());
        });
        if (previewCanvas != null) previewCanvas.setShowCollision(collisionCheckbox.isSelected());
        panel.add(collisionCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Grid toggle (kept in sync with the scene-overlay grid button; setGridVisible persists)
        gridCheckbox = new JCheckBox(get("viewer.showGrid"), settings.viewerBool("showGrid", true));
        gridCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        gridCheckbox.addActionListener(e -> setGridVisible(gridCheckbox.isSelected()));
        if (previewCanvas != null) previewCanvas.setShowGrid(gridCheckbox.isSelected());
        panel.add(gridCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Camera View checkbox
        CameraNode[] cameras = parsedModel.cameras();
        boolean hasCameras = cameras.length > 0;
        JCheckBox cameraViewCheckbox = new JCheckBox(get("viewer.cameraView"),
                hasCameras && settings.viewerBool("cameraView", false));
        cameraViewCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        cameraViewCheckbox.setEnabled(hasCameras);
        cameraViewCheckbox.addActionListener(e -> {
            if (previewCanvas != null) {
                if (cameraViewCheckbox.isSelected() && hasCameras) {
                    previewCanvas.applyCameraView(cameras[0]);
                } else {
                    previewCanvas.resetCameraView();
                }
            }
            persist("cameraView", cameraViewCheckbox.isSelected());
        });
        if (previewCanvas != null && cameraViewCheckbox.isSelected() && hasCameras) {
            previewCanvas.applyCameraView(cameras[0]);
        }
        panel.add(cameraViewCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Team color selector
        JPanel tcRow = flowRow(get("viewer.teamColor"));
        JComboBox<String> tcCombo = new JComboBox<>(TeamColorOptions.labels());
        int tcInit = Math.max(0, Math.min(tcCombo.getItemCount() - 1, settings.viewerInt("teamColor", 0)));
        tcCombo.setSelectedIndex(tcInit);
        tcCombo.setRenderer(new TeamColorComboRenderer(tcCombo, idx -> {
            int[] rgb = GameDataSource.getInstance().loadTeamColorRgb(idx, asset.path().getParent(), scanRoot);
            return rgb != null ? rgb : TeamColorOptions.fallbackRgb(idx);
        }));
        tcCombo.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setTeamColor(tcCombo.getSelectedIndex());
            persist("teamColor", tcCombo.getSelectedIndex());
        });
        if (previewCanvas != null) previewCanvas.setTeamColor(tcCombo.getSelectedIndex());
        tcRow.add(tcCombo);
        panel.add(tcRow);
        panel.add(Box.createVerticalStrut(6));

        // Reset every animation-tab parameter to its default state
        panel.add(new JSeparator(SwingConstants.HORIZONTAL));
        panel.add(Box.createVerticalStrut(6));
        JButton resetParamsBtn = new JButton(get("viewer.resetParams"));
        resetParamsBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        resetParamsBtn.setToolTipText(get("viewer.resetParamsTooltip"));
        resetParamsBtn.addActionListener(e -> {
            setChecked(loopCheckbox, true);
            speedSlider.setValue(100);
            nodeSizeSlider.setValue(30);
            setChecked(extentCheckbox, false);
            setChecked(bonesCheckbox, false);
            setChecked(helpersCheckbox, false);
            setChecked(attachmentsCheckbox, false);
            setChecked(ribbonsCheckbox, false);
            setChecked(particlesCheckbox, false);
            setChecked(nodeNamesCheckbox, false);
            setChecked(collisionCheckbox, false);
            setChecked(cameraViewCheckbox, false);
            tcCombo.setSelectedIndex(0);
            if (shadingCombo != null) shadingCombo.setSelectedIndex(1);
            setGridVisible(true);
        });
        panel.add(resetParamsBtn);
        panel.add(Box.createVerticalStrut(6));

        // Wire sequence combo
        final boolean[] suppressCallback = {false};
        seqCombo.addActionListener(e -> {
            if (suppressCallback[0]) return;
            if (previewCanvas != null) {
                int idx = seqCombo.getSelectedIndex();
                if (idx <= 0) { // index 0 == "None" — freeze in default pose
                    previewCanvas.setNoSequence();
                    playPauseBtn.setText(get("viewer.play"));
                    timeSlider.setValue(0);
                    timeSlider.setEnabled(false);
                    timeLabel.setText("0 ms");
                    persist("noSequence", true);
                    return;
                }
                int seqIdx = idx - 1;
                previewCanvas.setSequence(seqIdx);
                persist("noSequence", false);
                timeSlider.setEnabled(animData.hasAnimation());
                // Update scrubber range for new sequence
                if (seqIdx >= 0 && seqIdx < sequences.size()) {
                    SequenceInfo seq = sequences.get(seqIdx);
                    int dur = (int)(seq.end() - seq.start());
                    timeSlider.setMaximum(Math.max(1, dur));
                    timeSlider.setValue(0);
                    timeLabel.setText("0 ms");
                }
            }
        });

        // Sync combo when sequence changes via keyboard shortcut
        if (previewCanvas != null) {
            previewCanvas.setOnSequenceChanged(idx -> {
                int comboIdx = idx + 1; // sequence k maps to combo index k+1 (index 0 is "None")
                if (comboIdx >= 0 && comboIdx < seqCombo.getItemCount()) {
                    suppressCallback[0] = true;
                    seqCombo.setSelectedIndex(comboIdx);
                    suppressCallback[0] = false;
                }
                // Update scrubber range
                if (idx >= 0 && idx < sequences.size()) {
                    SequenceInfo seq = sequences.get(idx);
                    int dur = (int)(seq.end() - seq.start());
                    timeSlider.setMaximum(Math.max(1, dur));
                    timeSlider.setValue(0);
                    timeLabel.setText("0 ms");
                }
            });
        }

        // Restore the last sequence-selection state: "None" (index 0) or first sequence (index 1).
        if (!sequences.isEmpty()) {
            if (settings.viewerBool("noSequence", false)) {
                // Combo already defaults to index 0 (None), so setSelectedIndex(0) won't fire the
                // listener — apply the None UI state explicitly (canvas handled in the constructor).
                seqCombo.setSelectedIndex(0);
                playPauseBtn.setText(get("viewer.play"));
                timeSlider.setValue(0);
                timeSlider.setEnabled(false);
                timeLabel.setText("0 ms");
            } else {
                seqCombo.setSelectedIndex(1); // fires listener → setSequence(0) + scrubber range
            }
        }

        // Glue at bottom
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // ── Info tab ─────────────────────────────────────────────────────────

    private JScrollPane buildInfoTab() {
        NumberFormat nfmt = NumberFormat.getIntegerInstance();
        ModelMesh mesh = parsedModel.mesh();
        ModelAnimData animData = parsedModel.animData();
        ModelMetadata meta = parsedModel.metadata();

        String polygons = meta.polygonCount() >= 0
                ? nfmt.format(meta.polygonCount()) : get("main.na");

        String mName = meta.modelName();
        String[][] rows = {
                {get("viewer.info.file"), asset.fileName()},
                {get("viewer.info.modelName"), mName.isEmpty() ? get("main.na") : mName},
                {get("viewer.info.path"), asset.path().toString()},
                {get("viewer.info.size"), formatFileSize(asset.fileSizeBytes())},
                {get("viewer.info.vertices"), nfmt.format(mesh.vertices().length / 3)},
                {get("viewer.info.polygons"), polygons},
                {get("viewer.info.bones"), String.valueOf(animData.bones().length)},
                {get("viewer.info.geosets"), String.valueOf(animData.geosets().size())},
                {get("viewer.info.sequences"), String.valueOf(animData.sequences().size())},
                {get("viewer.info.textures"), String.valueOf(parsedModel.texData().length)},
                {get("viewer.info.boundingRadius"), String.format("%.2f", mesh.radius())},
        };

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        for (int r = 0; r < rows.length; r++) {
            gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0;
            JLabel key = new JLabel(rows[r][0]);
            key.setFont(key.getFont().deriveFont(Font.BOLD));
            grid.add(key, gbc);

            gbc.gridx = 1; gbc.weightx = 1.0;
            JLabel val = new JLabel(rows[r][1]);
            val.setToolTipText(rows[r][1]);
            grid.add(val, gbc);
        }

        // Push content to top
        gbc.gridx = 0; gbc.gridy = rows.length; gbc.weighty = 1.0;
        grid.add(Box.createGlue(), gbc);

        JScrollPane scroll = new JScrollPane(grid);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        return scroll;
    }

    // ── Textures tab ─────────────────────────────────────────────────────

    private JPanel buildTexturesTab() {
        GeosetTexData[] texData = parsedModel.texData();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        // Collect unique textures from all geoset layers
        Path modelDir = asset.path().getParent();
        GameDataSource gds = GameDataSource.getInstance();

        // Use LinkedHashMap to preserve insertion order and deduplicate by path+replaceableId
        java.util.LinkedHashMap<String, int[]> uniqueTextures = new java.util.LinkedHashMap<>();
        for (GeosetTexData td : texData) {
            if (!td.layers().isEmpty()) {
                for (GeosetTexData.LayerTexData layer : td.layers()) {
                    String key = layer.texturePath() + "|" + layer.replaceableId();
                    uniqueTextures.putIfAbsent(key, new int[]{layer.replaceableId()});
                }
            } else {
                String key = td.texturePath() + "|" + td.replaceableId();
                uniqueTextures.putIfAbsent(key, new int[]{td.replaceableId()});
            }
        }

        // Build display list from unique entries
        record TexEntry(String path, int replaceableId, String source) {}
        List<TexEntry> uniqueList = new java.util.ArrayList<>();
        for (var entry : uniqueTextures.entrySet()) {
            String path = entry.getKey().substring(0, entry.getKey().lastIndexOf('|'));
            int repId = entry.getValue()[0];
            String source;
            if (path.isEmpty()) {
                source = repId > 0 ? "REPLACEABLE" : "NONE";
            } else {
                source = gds.resolveTextureSource(path, repId, modelDir, scanRoot);
            }
            uniqueList.add(new TexEntry(path, repId, source));
        }

        JLabel header = new JLabel(fmt("viewer.textures.header", uniqueList.size()));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(new EmptyBorder(0, 0, 6, 0));
        panel.add(header, BorderLayout.NORTH);

        DefaultTableModel tableModel = new DefaultTableModel(
                new String[]{get("viewer.textures.colIndex"), get("viewer.textures.colPath"), get("viewer.textures.colSource")}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };

        for (int i = 0; i < uniqueList.size(); i++) {
            TexEntry te = uniqueList.get(i);
            tableModel.addRow(new Object[]{i, te.path().isEmpty() ? get("viewer.textures.noTexture") : te.path(), te.source()});
        }

        JTable table = new JTable(tableModel);
        table.getColumnModel().getColumn(0).setMaxWidth(30);
        table.getColumnModel().getColumn(2).setMaxWidth(100);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Color-coded source column
        table.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected && value instanceof String s) {
                    switch (s) {
                        case "DISK"        -> { c.setForeground(new Color(0, 140, 60));  c.setBackground(new Color(220, 255, 230)); }
                        case "CASC"        -> { c.setForeground(new Color(0, 80, 180));  c.setBackground(new Color(220, 235, 255)); }
                        case "MPQ"         -> { c.setForeground(new Color(100, 60, 160));c.setBackground(new Color(235, 225, 255)); }
                        case "REPLACEABLE" -> { c.setForeground(new Color(160, 120, 0)); c.setBackground(new Color(255, 245, 210)); }
                        case "MISSING"     -> { c.setForeground(new Color(180, 40, 40)); c.setBackground(new Color(255, 225, 225)); }
                        default            -> { c.setForeground(t.getForeground()); c.setBackground(t.getBackground()); }
                    }
                }
                return c;
            }
        });

        // Double-click to show texture popup, right-click to copy path
        javax.swing.JPopupMenu texPopup = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem copyPathItem = new javax.swing.JMenuItem(get("viewer.textures.copyPath"));
        copyPathItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                Object val = table.getValueAt(row, 1);
                if (val != null) {
                    java.awt.datatransfer.StringSelection sel =
                            new java.awt.datatransfer.StringSelection(val.toString());
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                }
            }
        });
        texPopup.add(copyPathItem);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < uniqueList.size()) {
                        List<String> paths = new java.util.ArrayList<>();
                        for (var te : uniqueList) paths.add(te.path());
                        showTexturePopup(paths, row);
                    }
                }
            }
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    table.setRowSelectionInterval(row, row);
                    texPopup.show(table, e.getX(), e.getY());
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);

        // Texture preview panel that scales to fill available space
        BufferedImage[] previewImg = {null};
        JPanel previewPanel = new JPanel(new BorderLayout());
        JLabel previewInfo = new JLabel("", SwingConstants.CENTER);
        previewInfo.setFont(previewInfo.getFont().deriveFont(Font.PLAIN, 11f));
        previewInfo.setBorder(new EmptyBorder(2, 4, 2, 4));

        JPanel imgPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (previewImg[0] == null) return;
                int pw = getWidth(), ph = getHeight();
                int iw = previewImg[0].getWidth(), ih = previewImg[0].getHeight();
                double scale = Math.min((double) pw / iw, (double) ph / ih);
                int dw = (int) (iw * scale), dh = (int) (ih * scale);
                int x = (pw - dw) / 2, y = (ph - dh) / 2;
                g.drawImage(previewImg[0], x, y, dw, dh, null);
            }
        };
        imgPanel.setBackground(Color.DARK_GRAY);
        previewPanel.add(previewInfo, BorderLayout.NORTH);
        previewPanel.add(imgPanel, BorderLayout.CENTER);

        // Split pane: table on top, preview on bottom, resizable
        javax.swing.JSplitPane splitPane = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, tableScroll, previewPanel);
        splitPane.setResizeWeight(0.4);
        splitPane.setDividerLocation(150);
        splitPane.setContinuousLayout(true);
        panel.add(splitPane, BorderLayout.CENTER);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row < 0 || row >= uniqueList.size()) {
                previewImg[0] = null;
                previewInfo.setText("");
                imgPanel.repaint();
                return;
            }
            String texPath = uniqueList.get(row).path();
            if (texPath.isEmpty()) {
                previewImg[0] = null;
                previewInfo.setText(get("viewer.textures.noTexture"));
                imgPanel.repaint();
                return;
            }
            BufferedImage img = GameDataSource.getInstance().loadTexture(texPath, modelDir, scanRoot);
            if (img != null) {
                previewImg[0] = img;
                previewInfo.setText(String.format("%s    %d x %d", texPath, img.getWidth(), img.getHeight()));
            } else {
                previewImg[0] = null;
                previewInfo.setText(get("viewer.textures.textureNotFound"));
            }
            imgPanel.repaint();
        });

        return panel;
    }

    // ── Materials tab ────────────────────────────────────────────────────

    private record MaterialNodeEntry(int materialIdx, String label) {
        @Override public String toString() { return label; }
    }
    private record LayerNodeEntry(int materialIdx, int layerIdx, String label) {
        @Override public String toString() { return label; }
    }

    private JPanel buildMaterialsTab() {
        MaterialInfo[] materials = parsedModel.materials();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel header = new JLabel(fmt("viewer.materials.header", materials.length));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(new EmptyBorder(0, 0, 6, 0));
        panel.add(header, BorderLayout.NORTH);

        if (materials.length == 0) {
            panel.add(new JLabel(get("viewer.materials.none"), JLabel.CENTER), BorderLayout.CENTER);
            return panel;
        }

        // Track which geosets use each material
        Map<Integer, List<Integer>> matToGeosets = new HashMap<>();
        ModelAnimData animData = parsedModel.animData();
        int gi = 0;
        for (var geoset : animData.geosets()) {
            if (geoset.vertexCount() > 0) {
                matToGeosets.computeIfAbsent(geoset.materialId(), k -> new java.util.ArrayList<>()).add(gi);
            }
            gi++;
        }

        // Track layer visibility (all visible by default)
        Map<Long, Boolean> layerEnabled = new HashMap<>();

        // Build a tree: each material is a parent node, layers are children
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Materials");

        for (MaterialInfo mat : materials) {
            StringBuilder matLabel = new StringBuilder();
            matLabel.append(fmt("viewer.materials.material", mat.index()));
            if (mat.priorityPlane() != 0) matLabel.append("  PP=").append(mat.priorityPlane());
            if (!mat.shader().isEmpty()) matLabel.append("  Shader=").append(mat.shader());
            List<Integer> usedBy = matToGeosets.getOrDefault(mat.index(), List.of());
            if (!usedBy.isEmpty()) {
                matLabel.append("  [Geoset ").append(
                        usedBy.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")))
                        .append("]");
            }

            List<String> matFlags = new java.util.ArrayList<>();
            if ((mat.flags() & 0x01) != 0) matFlags.add("ConstantColor");
            if ((mat.flags() & 0x10) != 0) matFlags.add("SortPrimsFarZ");
            if ((mat.flags() & 0x20) != 0) matFlags.add("FullResolution");
            if (!matFlags.isEmpty()) matLabel.append("  {").append(String.join(", ", matFlags)).append("}");

            DefaultMutableTreeNode matNode = new DefaultMutableTreeNode(
                    new MaterialNodeEntry(mat.index(), matLabel.toString()));

            for (int li = 0; li < mat.layers().size(); li++) {
                MaterialInfo.LayerInfo layer = mat.layers().get(li);
                StringBuilder layerLabel = new StringBuilder();
                layerLabel.append(fmt("viewer.materials.layer", li, layer.filterModeName()));

                if (!layer.texturePath().isEmpty()) {
                    layerLabel.append("  \"").append(layer.texturePath()).append("\"");
                } else if (layer.replaceableId() == 1) {
                    layerLabel.append("  TeamColor");
                } else if (layer.replaceableId() == 2) {
                    layerLabel.append("  TeamGlow");
                } else if (layer.replaceableId() > 0) {
                    layerLabel.append("  Replaceable(").append(layer.replaceableId()).append(")");
                } else {
                    layerLabel.append("  ").append(get("viewer.textures.noTexture"));
                }

                if (layer.alpha() < 1.0f) {
                    layerLabel.append(String.format("  Alpha=%.2f", layer.alpha()));
                }
                if (layer.textureAnimId() >= 0) {
                    layerLabel.append("  TexAnim=").append(layer.textureAnimId());
                }

                List<String> flags = new java.util.ArrayList<>();
                if (layer.isUnshaded())    flags.add("Unshaded");
                if (layer.isTwoSided())    flags.add("TwoSided");
                if (layer.isUnfogged())    flags.add("Unfogged");
                if (layer.isNoDepthTest()) flags.add("NoDepthTest");
                if (layer.isNoDepthSet())  flags.add("NoDepthSet");
                if (!flags.isEmpty()) layerLabel.append("  {").append(String.join(", ", flags)).append("}");

                long key = ((long) mat.index() << 16) | li;
                layerEnabled.put(key, true);
                matNode.add(new DefaultMutableTreeNode(
                        new LayerNodeEntry(mat.index(), li, layerLabel.toString())));
            }
            root.add(matNode);
        }

        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(Math.max(tree.getRowHeight(), ICON_SIZE + 4));

        // Custom renderer: color hints + eye toggle on layer nodes
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            private final JPanel layerPanel = new JPanel(new BorderLayout(4, 0));
            private final JLabel eyeLabel = new JLabel();
            {
                layerPanel.setOpaque(false);
                eyeLabel.setOpaque(false);
            }

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode dmtn) {
                    Object userObj = dmtn.getUserObject();
                    if (userObj instanceof MaterialNodeEntry) {
                        if (!sel) setForeground(new Color(40, 80, 160));
                    } else if (userObj instanceof LayerNodeEntry entry) {
                        if (!sel) {
                            String text = entry.label();
                            if (text.contains("TeamColor")) setForeground(new Color(180, 40, 40));
                            else if (text.contains("TeamGlow")) setForeground(new Color(200, 140, 0));
                            else if (text.contains("Additive") || text.contains("AddAlpha"))
                                setForeground(new Color(0, 130, 80));
                        }
                        // Eye toggle on the right
                        if (eyeEnabledIcon != null) {
                            long key = ((long) entry.materialIdx() << 16) | entry.layerIdx();
                            boolean enabled = layerEnabled.getOrDefault(key, true);
                            eyeLabel.setIcon(enabled ? eyeEnabledIcon : eyeDisabledIcon);
                            layerPanel.removeAll();
                            layerPanel.add(this, BorderLayout.CENTER);
                            layerPanel.add(eyeLabel, BorderLayout.EAST);
                            return layerPanel;
                        }
                    }
                }
                return this;
            }
        });

        // Selection listener: highlight geosets that use the selected material
        tree.addTreeSelectionListener(e -> {
            TreePath path = e.getNewLeadSelectionPath();
            if (path == null || previewCanvas == null) {
                if (previewCanvas != null) previewCanvas.setHighlightedGeosetIndices(null);
                return;
            }
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object userObj = node.getUserObject();
            int matIdx = -1;
            if (userObj instanceof MaterialNodeEntry me) matIdx = me.materialIdx();
            else if (userObj instanceof LayerNodeEntry le) matIdx = le.materialIdx();
            if (matIdx >= 0) {
                List<Integer> geosets = matToGeosets.getOrDefault(matIdx, List.of());
                previewCanvas.setHighlightedGeosetIndices(geosets.stream().mapToInt(Integer::intValue).toArray());
            } else {
                previewCanvas.setHighlightedGeosetIndices(null);
            }
        });

        // Click listener: toggle layer visibility when clicking the eye icon (right side)
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (!(node.getUserObject() instanceof LayerNodeEntry entry)) return;

                var bounds = tree.getPathBounds(path);
                if (bounds == null) return;
                int relativeX = e.getX() - bounds.x;
                if (relativeX < bounds.width - ICON_SIZE - 8) return;

                long key = ((long) entry.materialIdx() << 16) | entry.layerIdx();
                boolean nowEnabled = !layerEnabled.getOrDefault(key, true);
                layerEnabled.put(key, nowEnabled);
                if (previewCanvas != null) previewCanvas.setLayerVisible(entry.materialIdx(), entry.layerIdx(), nowEnabled);
                tree.repaint();
            }
        });

        // Expand all
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);

        JScrollPane scroll = new JScrollPane(tree);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    // ── Geosets tab ─────────────────────────────────────────────────────

    private JPanel buildGeosetsTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel header = new JLabel(get("viewer.geosets.header"));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(new EmptyBorder(0, 0, 6, 0));
        panel.add(header, BorderLayout.NORTH);

        GeosetTexData[] texData = parsedModel.texData();
        ModelAnimData animData = parsedModel.animData();
        NumberFormat nfmt = NumberFormat.getIntegerInstance();

        // Build geoset info entries
        DefaultListModel<GeosetEntry> listModel = new DefaultListModel<>();
        int gi = 0;
        for (GeosetSkinData skin : animData.geosets()) {
            int vc = skin.vertexCount();
            if (vc == 0) continue;
            if (gi >= texData.length) break;

            String texPath = texData[gi].texturePath();
            int replId = texData[gi].replaceableId();
            String replStr = replId == 1 ? "TeamColor" : replId == 2 ? "TeamGlow" : replId > 0 ? String.valueOf(replId) : null;

            StringBuilder detail = new StringBuilder();
            detail.append(fmt("viewer.geosets.verts", nfmt.format(vc)));
            if (skin.materialId() >= 0) detail.append("  ").append(fmt("viewer.geosets.mat", skin.materialId()));
            detail.append("  ").append(fmt("viewer.geosets.filter", filterModeName(texData[gi].filterMode())));
            if (replStr != null) detail.append("  ").append(fmt("viewer.geosets.repl", replStr));
            if (skin.hasSkinning()) detail.append("  ").append(get("viewer.geosets.skinned"));

            listModel.addElement(new GeosetEntry(gi, texPath.isEmpty() ? get("viewer.textures.noTexture") : texPath, detail.toString()));
            gi++;
        }

        if (listModel.isEmpty()) {
            panel.add(new JLabel(get("viewer.materials.noGeosetData"), JLabel.CENTER), BorderLayout.CENTER);
            return panel;
        }

        JList<GeosetEntry> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(-1); // variable height

        // Per-geoset visibility state (all visible by default)
        boolean[] visible = new boolean[listModel.size()];
        java.util.Arrays.fill(visible, true);

        // Custom renderer: two-line card per geoset with visibility checkbox
        list.setCellRenderer((jList, entry, index, isSelected, cellHasFocus) -> {
            JPanel cell = new JPanel(new BorderLayout(4, 0));
            cell.setBorder(new EmptyBorder(6, 8, 6, 8));

            JCheckBox cb = new JCheckBox();
            cb.setSelected(index < visible.length && visible[index]);
            cb.setOpaque(false);
            cell.add(cb, BorderLayout.WEST);

            JLabel title = new JLabel(fmt("viewer.geosets.geoset", entry.geosetIdx));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
            JLabel tex = new JLabel(entry.texturePath);
            tex.setFont(tex.getFont().deriveFont(Font.PLAIN, 11f));
            tex.setForeground(isSelected ? UIManager.getColor("List.selectionForeground") : new Color(100, 100, 100));
            JLabel detail = new JLabel(entry.detail);
            detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 10f));
            detail.setForeground(isSelected ? UIManager.getColor("List.selectionForeground") : new Color(130, 130, 130));

            JPanel textBlock = new JPanel();
            textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
            textBlock.setOpaque(false);
            textBlock.add(title);
            textBlock.add(tex);
            textBlock.add(detail);

            cell.add(textBlock, BorderLayout.CENTER);

            if (isSelected) {
                cell.setBackground(UIManager.getColor("List.selectionBackground"));
                title.setForeground(UIManager.getColor("List.selectionForeground"));
            } else {
                cell.setBackground(index % 2 == 0 ? UIManager.getColor("List.background") : new Color(245, 245, 250));
            }
            cell.setOpaque(true);
            return cell;
        });

        // Click listener: toggle visibility when clicking on the checkbox area
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0) return;
                var bounds = list.getCellBounds(idx, idx);
                if (bounds == null || !bounds.contains(e.getPoint())) return;
                // Check if click is in the checkbox region (first ~30px)
                int relX = e.getX() - bounds.x;
                if (relX <= 30) {
                    GeosetEntry entry = listModel.get(idx);
                    visible[idx] = !visible[idx];
                    if (previewCanvas != null) previewCanvas.setGeosetVisible(entry.geosetIdx, visible[idx]);
                    list.repaint();
                }
            }
            @Override
            public void mouseExited(MouseEvent e) {
                if (previewCanvas != null) previewCanvas.setHighlightedGeosetIdx(-1);
            }
        });

        // Hover listener: highlight geoset on the 3D mesh
        list.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx >= 0 && list.getCellBounds(idx, idx).contains(e.getPoint())) {
                    GeosetEntry entry = listModel.get(idx);
                    if (previewCanvas != null) previewCanvas.setHighlightedGeosetIdx(entry.geosetIdx);
                } else {
                    if (previewCanvas != null) previewCanvas.setHighlightedGeosetIdx(-1);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private record GeosetEntry(int geosetIdx, String texturePath, String detail) {
        @Override public String toString() { return fmt("viewer.geosets.geoset", geosetIdx); }
    }

    // ── UV Map tab ────────────────────────────────────────────────────────

    private boolean uvMapTabActive = false;

    private JPanel buildUVMapTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        GeosetTexData[] texData = parsedModel.texData();
        ModelAnimData animData = parsedModel.animData();
        ModelMesh mesh = parsedModel.mesh();

        // Build per-geoset local indices (same logic as GlPreviewCanvas.buildPerGeosetVaos)
        int geoCount = texData.length;
        int[][] perGeoIndices = new int[geoCount][];
        int[] perGeoVertOffset = new int[geoCount];
        {
            int[] allIndices = mesh.indices();
            int vertOffset = 0, indexOffset = 0, gi = 0;
            for (GeosetSkinData skin : animData.geosets()) {
                int vc = skin.vertexCount();
                if (vc == 0) continue;
                if (gi >= geoCount) break;
                perGeoVertOffset[gi] = vertOffset;
                int faceCount = 0;
                for (int ii = indexOffset; ii < allIndices.length; ii++) {
                    if (allIndices[ii] >= vertOffset && allIndices[ii] < vertOffset + vc) faceCount++;
                    else break;
                }
                if (faceCount > 0) {
                    int[] indices = new int[faceCount];
                    for (int ii = 0; ii < faceCount; ii++) indices[ii] = allIndices[indexOffset + ii] - vertOffset;
                    perGeoIndices[gi] = indices;
                    indexOffset += faceCount;
                }
                vertOffset += vc;
                gi++;
            }
        }

        // Geoset selector combo
        JComboBox<String> geoCombo = new JComboBox<>();
        for (int gi = 0; gi < geoCount; gi++) {
            String texName = texData[gi].texturePath();
            if (texName.isEmpty()) texName = get("viewer.textures.noTexture");
            int sep = Math.max(texName.lastIndexOf('\\'), texName.lastIndexOf('/'));
            if (sep >= 0) texName = texName.substring(sep + 1);
            geoCombo.addItem(fmt("viewer.uvmap.geosetItem", gi, texName));
        }

        // Options row
        JCheckBox alphaToggle = new JCheckBox(get("viewer.uvmap.alpha"), false);
        alphaToggle.setToolTipText(get("viewer.uvmap.alphaTooltip"));
        JCheckBox highlightToggle = new JCheckBox(get("viewer.uvmap.highlight"), true);
        highlightToggle.setToolTipText(get("viewer.uvmap.highlightTooltip"));
        JComboBox<String> highlightMode = new JComboBox<>(new String[]{get("viewer.uvmap.overlay"), get("viewer.uvmap.wireframe")});
        highlightMode.setToolTipText(get("viewer.uvmap.highlightStyleTooltip"));

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        JPanel geoRow = new JPanel(new BorderLayout(4, 0));
        geoRow.add(new JLabel(get("viewer.uvmap.geoset")), BorderLayout.WEST);
        geoRow.add(geoCombo, BorderLayout.CENTER);

        JCheckBox vertexDotsToggle = new JCheckBox(get("viewer.uvmap.vertices"), false);
        vertexDotsToggle.setToolTipText(get("viewer.uvmap.verticesTooltip"));

        JPanel optRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        optRow.add(alphaToggle);
        optRow.add(vertexDotsToggle);
        optRow.add(highlightToggle);
        optRow.add(highlightMode);

        topPanel.add(geoRow);
        topPanel.add(optRow);
        topPanel.setBorder(new EmptyBorder(0, 0, 4, 0));
        panel.add(topPanel, BorderLayout.NORTH);

        // Texture + UV cache
        BufferedImage[] cachedTex = {null};
        int[] selectedGeo = {-1};
        Path modelDir = asset.path().getParent();

        // Zoom/pan state
        float[] zoom = {1.0f};
        float[] panOffX = {0f}, panOffY = {0f};
        int[] lastDragX = {0}, lastDragY = {0};
        boolean[] dragging = {false};

        // UV drawing panel
        JPanel uvPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                int gi = selectedGeo[0];
                if (gi < 0 || gi >= geoCount) return;

                int pw = getWidth(), ph = getHeight();
                int baseSize = Math.min(pw, ph);
                float z = zoom[0];
                int size = (int) (baseSize * z);
                int ox = (pw - baseSize) / 2 + (int) panOffX[0] - (size - baseSize) / 2;
                int oy = (ph - baseSize) / 2 + (int) panOffY[0] - (size - baseSize) / 2;

                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.clipRect(0, 0, pw, ph);

                // Draw texture background
                if (alphaToggle.isSelected()) {
                    int cs = Math.max(4, (int) (8 * z));
                    for (int cy2 = Math.max(0, oy); cy2 < Math.min(ph, oy + size); cy2 += cs) {
                        for (int cx2 = Math.max(0, ox); cx2 < Math.min(pw, ox + size); cx2 += cs) {
                            boolean light = ((cx2 - ox) / cs + (cy2 - oy) / cs) % 2 == 0;
                            g2.setColor(light ? new Color(180, 180, 180) : new Color(120, 120, 120));
                            g2.fillRect(cx2, cy2, Math.min(cs, ox + size - cx2), Math.min(cs, oy + size - cy2));
                        }
                    }
                }
                if (cachedTex[0] != null) {
                    g2.drawImage(cachedTex[0], ox, oy, size, size, null);
                } else if (!alphaToggle.isSelected()) {
                    g2.setColor(new Color(40, 40, 40));
                    g2.fillRect(ox, oy, size, size);
                }

                // Draw UV wireframe — deduplicate edges to prevent color stacking
                float[] uvs = texData[gi].uvs();
                int[] indices = perGeoIndices[gi];
                if (uvs != null && indices != null && indices.length >= 3) {
                    // Collect unique edges (ordered pair of vertex indices)
                    java.util.Set<Long> drawnEdges = new java.util.HashSet<>();
                    g2.setColor(new Color(0, 255, 0, 180));
                    g2.setStroke(new BasicStroke(1.0f));
                    for (int t = 0; t + 2 < indices.length; t += 3) {
                        int i0 = indices[t], i1 = indices[t + 1], i2 = indices[t + 2];
                        if (i0 * 2 + 1 >= uvs.length || i1 * 2 + 1 >= uvs.length || i2 * 2 + 1 >= uvs.length)
                            continue;
                        int x0 = ox + (int) (uvs[i0 * 2] * size), y0 = oy + (int) (uvs[i0 * 2 + 1] * size);
                        int x1 = ox + (int) (uvs[i1 * 2] * size), y1 = oy + (int) (uvs[i1 * 2 + 1] * size);
                        int x2 = ox + (int) (uvs[i2 * 2] * size), y2 = oy + (int) (uvs[i2 * 2 + 1] * size);
                        drawEdgeOnce(g2, drawnEdges, i0, i1, x0, y0, x1, y1);
                        drawEdgeOnce(g2, drawnEdges, i1, i2, x1, y1, x2, y2);
                        drawEdgeOnce(g2, drawnEdges, i2, i0, x2, y2, x0, y0);
                    }

                    // Draw vertex dots
                    if (vertexDotsToggle.isSelected()) {
                        g2.setColor(new Color(255, 255, 0, 220));
                        int dotSize = Math.max(2, (int) (3 * z));
                        java.util.Set<Integer> drawnVerts = new java.util.HashSet<>();
                        for (int idx : indices) {
                            if (idx * 2 + 1 >= uvs.length || !drawnVerts.add(idx)) continue;
                            int vx = ox + (int) (uvs[idx * 2] * size);
                            int vy = oy + (int) (uvs[idx * 2 + 1] * size);
                            g2.fillOval(vx - dotSize / 2, vy - dotSize / 2, dotSize, dotSize);
                        }
                    }
                }

                // Draw UV space border
                g2.setColor(Color.GRAY);
                g2.drawRect(ox, oy, size, size);
                g2.dispose();
            }
        };
        uvPanel.setBackground(Color.DARK_GRAY);

        // Zoom with mouse wheel
        uvPanel.addMouseWheelListener(e -> {
            float oldZoom = zoom[0];
            if (e.getWheelRotation() < 0) zoom[0] = Math.min(zoom[0] * 1.15f, 20f);
            else zoom[0] = Math.max(zoom[0] / 1.15f, 0.5f);
            // Zoom toward mouse position
            float zoomRatio = zoom[0] / oldZoom;
            int mx = e.getX() - uvPanel.getWidth() / 2;
            int my = e.getY() - uvPanel.getHeight() / 2;
            panOffX[0] = (panOffX[0] - mx) * zoomRatio + mx;
            panOffY[0] = (panOffY[0] - my) * zoomRatio + my;
            uvPanel.repaint();
        });

        // Pan with right-click drag, double-right-click to reset
        uvPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    dragging[0] = true;
                    lastDragX[0] = e.getX();
                    lastDragY[0] = e.getY();
                }
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { dragging[0] = false; }
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && e.getClickCount() == 2) {
                    zoom[0] = 1.0f; panOffX[0] = 0f; panOffY[0] = 0f;
                    uvPanel.repaint();
                }
            }
        });
        uvPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (dragging[0]) {
                    panOffX[0] += e.getX() - lastDragX[0];
                    panOffY[0] += e.getY() - lastDragY[0];
                    lastDragX[0] = e.getX();
                    lastDragY[0] = e.getY();
                    uvPanel.repaint();
                }
            }
        });

        // Layer selector for multi-layer geosets
        JComboBox<String> layerCombo = new JComboBox<>();
        JPanel layerRow = new JPanel(new BorderLayout(4, 0));
        layerRow.setBorder(new EmptyBorder(4, 0, 0, 0));
        layerRow.add(new JLabel(get("viewer.uvmap.layer")), BorderLayout.WEST);
        layerRow.add(layerCombo, BorderLayout.CENTER);
        layerRow.setVisible(false);

        JPanel bottomRow = new JPanel(new BorderLayout());
        bottomRow.add(layerRow, BorderLayout.NORTH);

        JLabel infoLabel = new JLabel(" ", SwingConstants.CENTER);
        infoLabel.setFont(infoLabel.getFont().deriveFont(Font.PLAIN, 10f));
        infoLabel.setBorder(new EmptyBorder(2, 0, 0, 0));
        bottomRow.add(infoLabel, BorderLayout.SOUTH);

        panel.add(uvPanel, BorderLayout.CENTER);
        panel.add(bottomRow, BorderLayout.SOUTH);

        // Apply highlight to 3D view based on current settings
        Runnable applyHighlight = () -> {
            if (previewCanvas == null) return;
            if (!uvMapTabActive || !highlightToggle.isSelected()) {
                previewCanvas.setHighlightedGeosetIdx(-1);
                previewCanvas.setHighlightWireframe(false);
                return;
            }
            int gi = geoCombo.getSelectedIndex();
            previewCanvas.setHighlightWireframe(highlightMode.getSelectedIndex() == 1);
            previewCanvas.setHighlightedGeosetIdx(gi);
        };

        // Selection handler
        Runnable updateUV = () -> {
            int gi = geoCombo.getSelectedIndex();
            selectedGeo[0] = gi;
            if (gi < 0 || gi >= geoCount) {
                cachedTex[0] = null;
                layerRow.setVisible(false);
                infoLabel.setText(" ");
                uvPanel.repaint();
                return;
            }

            // Update layer combo
            var layers = texData[gi].layers();
            layerCombo.removeAllItems();
            if (layers.size() > 1) {
                for (int li = 0; li < layers.size(); li++) {
                    var layer = layers.get(li);
                    String name = layer.texturePath();
                    int sep = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
                    if (sep >= 0) name = name.substring(sep + 1);
                    layerCombo.addItem(fmt("viewer.uvmap.layerItem", li, name));
                }
                layerRow.setVisible(true);
            } else {
                layerRow.setVisible(false);
            }

            // Load texture
            String texPath = texData[gi].texturePath();
            if (!texPath.isEmpty()) {
                cachedTex[0] = GameDataSource.getInstance().loadTexture(texPath, modelDir, scanRoot);
            } else {
                cachedTex[0] = null;
            }

            // Info
            float[] uvs = texData[gi].uvs();
            int[] indices = perGeoIndices[gi];
            int triCount = indices != null ? indices.length / 3 : 0;
            int vertCount = uvs != null ? uvs.length / 2 : 0;
            infoLabel.setText(fmt("viewer.uvmap.info", vertCount, triCount));

            applyHighlight.run();
            uvPanel.repaint();
        };

        // Layer change handler — reload texture for selected layer
        layerCombo.addActionListener(e -> {
            int gi = geoCombo.getSelectedIndex();
            int li = layerCombo.getSelectedIndex();
            if (gi < 0 || gi >= geoCount || li < 0) return;
            var layers = texData[gi].layers();
            if (li >= layers.size()) return;
            var layer = layers.get(li);
            String texPath = layer.texturePath();
            if (!texPath.isEmpty()) {
                cachedTex[0] = GameDataSource.getInstance().loadTexture(texPath, modelDir, scanRoot);
            } else {
                cachedTex[0] = null;
            }
            uvPanel.repaint();
        });

        geoCombo.addActionListener(e -> updateUV.run());
        alphaToggle.addActionListener(e -> uvPanel.repaint());
        vertexDotsToggle.addActionListener(e -> uvPanel.repaint());
        highlightToggle.addActionListener(e -> applyHighlight.run());
        highlightMode.addActionListener(e -> applyHighlight.run());
        if (geoCount > 0) {
            geoCombo.setSelectedIndex(0);
        }

        // Track tab visibility
        panel.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                uvMapTabActive = panel.isShowing();
                applyHighlight.run();
            }
        });

        return panel;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void showErrorStackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        JTextArea area = new JTextArea(sw.toString());
        area.setEditable(false);
        area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(700, 400));
        JOptionPane.showMessageDialog(this, scroll, fmt("viewer.renderError", asset.fileName()), JOptionPane.ERROR_MESSAGE);
    }

    /** Draw an edge only once — deduplicates shared triangle edges. */
    private static void drawEdgeOnce(Graphics2D g2, java.util.Set<Long> drawn, int a, int b,
                                     int x0, int y0, int x1, int y1) {
        long key = Math.min(a, b) * 100_000L + Math.max(a, b);
        if (drawn.add(key)) g2.drawLine(x0, y0, x1, y1);
    }

    // ── Nodes tab ─────────────────────────────────────────────────────────

    private static final int ICON_SIZE = 16;
    private static ImageIcon eyeEnabledIcon, eyeDisabledIcon;
    private static ImageIcon boneIcon, helperIcon, attachmentIcon, ribbonIcon, particle2Icon;
    static {
        try {
            var enabledImg = ImageIO.read(ModelViewerDialog.class.getResourceAsStream("/images/observer-icon-hover.png"));
            var disabledImg = ImageIO.read(ModelViewerDialog.class.getResourceAsStream("/images/observer-icon-disabled.png"));
            eyeEnabledIcon = new ImageIcon(enabledImg.getScaledInstance(ICON_SIZE, ICON_SIZE, Image.SCALE_SMOOTH));
            eyeDisabledIcon = new ImageIcon(disabledImg.getScaledInstance(ICON_SIZE, ICON_SIZE, Image.SCALE_SMOOTH));
        } catch (Exception ex) {
            eyeEnabledIcon = null;
            eyeDisabledIcon = null;
        }
        try {
            boneIcon       = loadNodeIcon("/images/bone.png");
            helperIcon     = loadNodeIcon("/images/helperhand.png");
            attachmentIcon = loadNodeIcon("/images/attachment.png");
            ribbonIcon     = loadNodeIcon("/images/ribbon.png");
            particle2Icon  = loadNodeIcon("/images/particle2.png");
        } catch (Exception ex) {
            boneIcon = helperIcon = attachmentIcon = ribbonIcon = particle2Icon = null;
        }
    }

    private static ImageIcon loadNodeIcon(String resourcePath) throws Exception {
        var img = ImageIO.read(ModelViewerDialog.class.getResourceAsStream(resourcePath));
        return new ImageIcon(img.getScaledInstance(ICON_SIZE, ICON_SIZE, Image.SCALE_SMOOTH));
    }

    private JScrollPane buildNodesTab() {
        BoneNode[] bones = parsedModel.animData().bones();

        // Track which emitters are enabled (by objectId)
        Map<Integer, Boolean> emitterEnabled = new HashMap<>();

        // Build tree nodes indexed by objectId
        Map<Integer, DefaultMutableTreeNode> nodeMap = new HashMap<>();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Model");

        for (BoneNode bone : bones) {
            String label = bone.name().isEmpty()
                    ? bone.nodeType() + " #" + bone.objectId()
                    : bone.name();
            DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(new NodeEntry(bone, label));
            nodeMap.put(bone.objectId(), treeNode);
            if (bone.nodeType() == BoneNode.NodeType.RIBBON_EMITTER
                    || bone.nodeType() == BoneNode.NodeType.PARTICLE_EMITTER2) {
                emitterEnabled.put(bone.objectId(), true);
            }
        }

        // Build hierarchy
        for (BoneNode bone : bones) {
            DefaultMutableTreeNode treeNode = nodeMap.get(bone.objectId());
            if (bone.parentId() < 0 || !nodeMap.containsKey(bone.parentId())) {
                root.add(treeNode);
            } else {
                nodeMap.get(bone.parentId()).add(treeNode);
            }
        }

        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(Math.max(tree.getRowHeight(), ICON_SIZE + 4));

        // Custom renderer: node type icon on LEFT, name in center, eye toggle on RIGHT
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            private final JPanel panel = new JPanel(new BorderLayout(4, 0));
            private final JLabel eyeLabel = new JLabel();
            {
                panel.setOpaque(false);
                eyeLabel.setOpaque(false);
            }

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode dmtn
                        && dmtn.getUserObject() instanceof NodeEntry entry) {
                    setText(entry.label);

                    // Node type icon on the left (replaces default tree icon)
                    ImageIcon nodeIcon = switch (entry.bone.nodeType()) {
                        case BONE             -> boneIcon;
                        case HELPER           -> helperIcon;
                        case ATTACHMENT       -> attachmentIcon;
                        case RIBBON_EMITTER   -> ribbonIcon;
                        case PARTICLE_EMITTER2 -> particle2Icon;
                    };
                    if (nodeIcon != null) setIcon(nodeIcon);

                    // Eye toggle on the right for emitter nodes
                    if (emitterEnabled.containsKey(entry.bone.objectId()) && eyeEnabledIcon != null) {
                        boolean enabled = emitterEnabled.getOrDefault(entry.bone.objectId(), true);
                        eyeLabel.setIcon(enabled ? eyeEnabledIcon : eyeDisabledIcon);
                        panel.removeAll();
                        panel.add(this, BorderLayout.CENTER);
                        panel.add(eyeLabel, BorderLayout.EAST);
                        return panel;
                    }
                }
                return this;
            }
        });

        // Click listener: toggle emitter visibility when clicking the eye icon (right side)
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                Object userObj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (!(userObj instanceof NodeEntry entry)) return;
                if (!emitterEnabled.containsKey(entry.bone.objectId())) return;

                // Check if click is in the eye icon area (right side of the row)
                var bounds = tree.getPathBounds(path);
                if (bounds == null) return;
                int relativeX = e.getX() - bounds.x;
                if (relativeX < bounds.width - ICON_SIZE - 8) return;

                boolean nowEnabled = !emitterEnabled.get(entry.bone.objectId());
                emitterEnabled.put(entry.bone.objectId(), nowEnabled);
                if (previewCanvas != null) previewCanvas.setEmitterEnabled(entry.bone.objectId(), nowEnabled);
                tree.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (previewCanvas != null) previewCanvas.setHighlightedBoneId(-1);
            }
        });

        // Hover listener: highlight bone on the 3D mesh
        tree.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    Object node = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                    if (node instanceof NodeEntry entry) {
                        if (previewCanvas != null) previewCanvas.setHighlightedBoneId(entry.bone.objectId());
                        return;
                    }
                }
                if (previewCanvas != null) previewCanvas.setHighlightedBoneId(-1);
            }
        });

        // Expand all by default
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }

        JScrollPane scroll = new JScrollPane(tree);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        return scroll;
    }

    private record NodeEntry(BoneNode bone, String label) {
        @Override public String toString() { return label; }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static JComboBox<String> buildSequenceCombo(List<SequenceInfo> sequences) {
        if (sequences.isEmpty()) {
            return new JComboBox<>(new String[]{get("viewer.noSequences")});
        }
        // "None" (default pose, no animation) is index 0; real sequences follow at 1..n.
        String[] labels = new String[sequences.size() + 1];
        labels[0] = get("viewer.sequenceNone");
        for (int i = 0; i < sequences.size(); i++) labels[i + 1] = sequences.get(i).displayLabel();
        return new JComboBox<>(labels);
    }

    private static JPanel flowRow(String... labelTexts) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        for (String text : labelTexts) {
            row.add(new JLabel(text));
        }
        return row;
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return fmt("filesize.bytes", bytes);
        if (bytes < 1024 * 1024) return fmt("filesize.kb", String.format("%.1f", bytes / 1024.0));
        return fmt("filesize.mb", String.format("%.1f", bytes / (1024.0 * 1024.0)));
    }

    private void showTexturePopup(List<String> allPaths, int startIndex) {
        // Filter to non-blank paths for navigation
        List<String> texPaths = new java.util.ArrayList<>();
        for (String p : allPaths) {
            if (!p.isBlank()) texPaths.add(p);
        }
        if (texPaths.isEmpty()) return;

        // Find starting position
        String startPath = (startIndex >= 0 && startIndex < allPaths.size()) ? allPaths.get(startIndex) : "";
        int[] currentPos = {0};
        for (int i = 0; i < texPaths.size(); i++) {
            if (texPaths.get(i).equals(startPath)) { currentPos[0] = i; break; }
        }

        JDialog popup = new JDialog(this, get("viewer.texPopup.title"), true);
        popup.setLayout(new BorderLayout(4, 4));

        // State
        boolean[] showAlpha = {false};
        boolean[] showCheckerboard = {true};
        BufferedImage[] currentImg = {null};
        double[] zoom = {1.0};
        double[] panX = {0}, panY = {0};
        int[] dragStart = {0, 0};
        double[] panStart = {0, 0};

        JLabel infoLabel = new JLabel("", SwingConstants.CENTER);
        infoLabel.setBorder(new EmptyBorder(6, 12, 2, 12));
        infoLabel.setFont(infoLabel.getFont().deriveFont(Font.PLAIN, 11f));

        JPanel imgPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                BufferedImage img = currentImg[0];
                if (img == null) return;

                if (showCheckerboard[0]) {
                    drawCheckerboard(g, getWidth(), getHeight());
                }

                int pw = getWidth(), ph = getHeight();
                int iw = img.getWidth(), ih = img.getHeight();
                // Fit scale, then apply user zoom
                double fitScale = Math.min((double) pw / iw, (double) ph / ih);
                double scale = fitScale * zoom[0];
                int dw = (int) (iw * scale), dh = (int) (ih * scale);
                int x = (int) ((pw - dw) / 2.0 + panX[0]);
                int y = (int) ((ph - dh) / 2.0 + panY[0]);
                ((Graphics2D) g).setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        zoom[0] > 2.0 ? java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                                      : java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, x, y, dw, dh, null);
            }
        };
        imgPanel.setBackground(new Color(60, 60, 60));

        // Mouse wheel zoom (zoom toward cursor)
        imgPanel.addMouseWheelListener(e -> {
            double oldZoom = zoom[0];
            double factor = e.getWheelRotation() < 0 ? 1.15 : 1.0 / 1.15;
            zoom[0] = Math.max(0.1, Math.min(50.0, zoom[0] * factor));
            // Zoom toward cursor position
            double mx = e.getX() - imgPanel.getWidth() / 2.0 - panX[0];
            double my = e.getY() - imgPanel.getHeight() / 2.0 - panY[0];
            double ratio = 1.0 - zoom[0] / oldZoom;
            panX[0] += mx * ratio;
            panY[0] += my * ratio;
            imgPanel.repaint();
        });

        // Mouse drag to pan
        imgPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                dragStart[0] = e.getX(); dragStart[1] = e.getY();
                panStart[0] = panX[0]; panStart[1] = panY[0];
                imgPanel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                imgPanel.setCursor(java.awt.Cursor.getDefaultCursor());
            }
        });
        imgPanel.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                panX[0] = panStart[0] + (e.getX() - dragStart[0]);
                panY[0] = panStart[1] + (e.getY() - dragStart[1]);
                imgPanel.repaint();
            }
        });

        Runnable refreshImage = () -> {
            String texPath = texPaths.get(currentPos[0]);
            popup.setTitle(fmt("viewer.texPopup.texture", texPath, currentPos[0] + 1, texPaths.size()));

            Path modelDir = asset.path().getParent();
            BufferedImage img = GameDataSource.getInstance().loadTexture(texPath, modelDir, scanRoot);
            if (img == null) {
                infoLabel.setText(fmt("viewer.texPopup.notFound", texPath));
                currentImg[0] = null;
                imgPanel.repaint();
                return;
            }

            BufferedImage display = showAlpha[0] ? extractAlphaChannel(img) : img;
            currentImg[0] = display;

            infoLabel.setText(fmt("viewer.texPopup.info",
                    texPath, img.getWidth(), img.getHeight(), imageTypeName(img.getType()), (int)(zoom[0] * 100)));

            imgPanel.repaint();
        };

        // Reset zoom/pan on texture change
        Runnable resetAndRefresh = () -> {
            zoom[0] = 1.0; panX[0] = 0; panY[0] = 0;
            refreshImage.run();
        };

        // Navigation + toggles toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        JButton prevBtn = new JButton("<");
        JButton nextBtn = new JButton(">");
        JButton resetBtn = new JButton("1:1");
        JCheckBox alphaCb = new JCheckBox(get("viewer.texPopup.alpha"));
        JCheckBox checkerCb = new JCheckBox(get("viewer.texPopup.checkerboard"), true);

        prevBtn.addActionListener(e -> {
            currentPos[0] = (currentPos[0] - 1 + texPaths.size()) % texPaths.size();
            resetAndRefresh.run();
        });
        nextBtn.addActionListener(e -> {
            currentPos[0] = (currentPos[0] + 1) % texPaths.size();
            resetAndRefresh.run();
        });
        resetBtn.setToolTipText(get("viewer.texPopup.resetZoom"));
        resetBtn.addActionListener(e -> resetAndRefresh.run());
        alphaCb.addActionListener(e -> { showAlpha[0] = alphaCb.isSelected(); refreshImage.run(); });
        checkerCb.addActionListener(e -> { showCheckerboard[0] = checkerCb.isSelected(); imgPanel.repaint(); });

        prevBtn.setEnabled(texPaths.size() > 1);
        nextBtn.setEnabled(texPaths.size() > 1);
        toolbar.add(prevBtn);
        toolbar.add(nextBtn);
        toolbar.add(resetBtn);
        toolbar.add(alphaCb);
        toolbar.add(checkerCb);

        popup.add(infoLabel, BorderLayout.NORTH);
        popup.add(imgPanel, BorderLayout.CENTER);
        popup.add(toolbar, BorderLayout.SOUTH);
        popup.setSize(new Dimension(560, 620));
        resetAndRefresh.run();
        popup.setLocationRelativeTo(this);
        popup.setVisible(true);
    }

    /** Draws a checkerboard pattern (transparency indicator). */
    private static void drawCheckerboard(Graphics g, int w, int h) {
        int sz = 12;
        for (int y = 0; y < h; y += sz) {
            for (int x = 0; x < w; x += sz) {
                g.setColor(((x / sz + y / sz) % 2 == 0) ? new Color(204, 204, 204) : Color.WHITE);
                g.fillRect(x, y, sz, sz);
            }
        }
    }

    /** Extracts the alpha channel as a grayscale image. */
    private static BufferedImage extractAlphaChannel(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage alpha = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = (src.getRGB(x, y) >> 24) & 0xFF;
                alpha.setRGB(x, y, (a << 16) | (a << 8) | a);
            }
        }
        return alpha;
    }

    private static String filterModeName(int mode) {
        return switch (mode) {
            case 0 -> get("viewer.filter.none");
            case 1 -> get("viewer.filter.transparent");
            case 2 -> get("viewer.filter.blend");
            case 3 -> get("viewer.filter.additive");
            case 4 -> get("viewer.filter.addAlpha");
            case 5 -> get("viewer.filter.modulate");
            case 6 -> get("viewer.filter.modulate2x");
            default -> fmt("viewer.filter.unknown", mode);
        };
    }

    private static String imageTypeName(int type) {
        return switch (type) {
            case BufferedImage.TYPE_INT_ARGB   -> get("viewer.imgType.argb");
            case BufferedImage.TYPE_INT_RGB    -> get("viewer.imgType.rgb");
            case BufferedImage.TYPE_4BYTE_ABGR -> get("viewer.imgType.4babgr");
            case BufferedImage.TYPE_3BYTE_BGR  -> get("viewer.imgType.3bbgr");
            case BufferedImage.TYPE_BYTE_INDEXED -> get("viewer.imgType.indexed");
            default -> fmt("viewer.imgType.unknown", type);
        };
    }
}
