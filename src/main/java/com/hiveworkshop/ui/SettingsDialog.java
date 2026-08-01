package com.hiveworkshop.ui;

import static com.hiveworkshop.i18n.Messages.get;
import static com.hiveworkshop.i18n.Messages.fmt;
import com.hiveworkshop.i18n.Messages;
import com.hiveworkshop.model.ReterasParsedModel;
import com.hiveworkshop.parser.AppLogBuffer;
import com.hiveworkshop.parser.AppSettings;
import com.hiveworkshop.parser.GameDataSource;
import com.hiveworkshop.parser.ReterasModelParser;
import com.hiveworkshop.model.ExternalProgram;
import com.hiveworkshop.model.ThumbnailQuality;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import javax.swing.JColorChooser;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SettingsDialog extends JDialog {

    // ── Theme entries ─────────────────────────────────────────────────────────

    private record ThemeEntry(String displayName, String className) {
        @Override public String toString() { return displayName; }
    }

    private static final ThemeEntry[] THEMES = buildThemeList();

    private static ThemeEntry[] buildThemeList() {
        List<ThemeEntry> list = new ArrayList<>();
        list.add(new ThemeEntry("Metal (Default)",  "javax.swing.plaf.metal.MetalLookAndFeel"));
        list.add(new ThemeEntry("Nimbus",           "javax.swing.plaf.nimbus.NimbusLookAndFeel"));
        list.add(new ThemeEntry("System",           UIManager.getSystemLookAndFeelClassName()));
        // Classic Windows L&F (available on Windows only)
        try {
            Class.forName("com.sun.java.swing.plaf.windows.WindowsClassicLookAndFeel");
            list.add(new ThemeEntry("Windows Classic", "com.sun.java.swing.plaf.windows.WindowsClassicLookAndFeel"));
        } catch (ClassNotFoundException ignored) {}
        try {
            Class.forName("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
            list.add(new ThemeEntry("Windows",         "com.sun.java.swing.plaf.windows.WindowsLookAndFeel"));
        } catch (ClassNotFoundException ignored) {}
        // Motif (cross-platform)
        try {
            Class.forName("com.sun.java.swing.plaf.motif.MotifLookAndFeel");
            list.add(new ThemeEntry("Motif (CDE)",     "com.sun.java.swing.plaf.motif.MotifLookAndFeel"));
        } catch (ClassNotFoundException ignored) {}
        // FlatLaf themes
        list.add(new ThemeEntry("FlatLaf Light",    "com.formdev.flatlaf.FlatLightLaf"));
        list.add(new ThemeEntry("FlatLaf Dark",     "com.formdev.flatlaf.FlatDarkLaf"));
        list.add(new ThemeEntry("FlatLaf IntelliJ", "com.formdev.flatlaf.FlatIntelliJLaf"));
        list.add(new ThemeEntry("FlatLaf Darcula",  "com.formdev.flatlaf.FlatDarculaLaf"));
        return list.toArray(new ThemeEntry[0]);
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final AppSettings settings;
    private final JTextField cascPathField = new JTextField();
    private final DefaultListModel<String> mpqListModel = new DefaultListModel<>();
    private final JList<String> mpqList = new JList<>(mpqListModel);
    private final JComboBox<ThemeEntry> themeCombo = new JComboBox<>(THEMES);
    private final JButton bgColorButton = new JButton();
    private Color bgColor = new Color(0x0F, 0x14, 0x19);
    private final AngleDial yawDial      = new AngleDial(56, AppSettings.DEFAULT_CAMERA_YAW, get("settings.yaw"));
    private final AngleDial pitchDial    = new AngleDial(56, AppSettings.DEFAULT_CAMERA_PITCH, -89, 89, get("settings.pitch"));
    private final JTextField animNameField = new JTextField("Stand", 12);
    private final JComboBox<ThumbnailQuality> qualityCombo = new JComboBox<>(ThumbnailQuality.values());
    private record LanguageEntry(String code, String label) {
        @Override public String toString() { return label; }
    }
    private static final LanguageEntry[] LANGUAGES = {
            new LanguageEntry("en", "English"),
            new LanguageEntry("fr", "Français"),
            new LanguageEntry("es", "Español"),
            new LanguageEntry("zh-CN", "简体中文"),
    };
    private final JComboBox<LanguageEntry> languageCombo = new JComboBox<>(LANGUAGES);
    private final DefaultListModel<ExternalProgram> extProgListModel = new DefaultListModel<>();
    private final JList<ExternalProgram> extProgList = new JList<>(extProgListModel);
    private final javax.swing.JCheckBox tagsEnabledCheckbox = new javax.swing.JCheckBox(get("settings.tags.enable"));
    private final DefaultListModel<String> removedTagsListModel = new DefaultListModel<>();
    private final JList<String> removedTagsList = new JList<>(removedTagsListModel);
    private GlPreviewCanvas cameraPreview;  // small 3D preview in Camera tab
    private Path previewTempFile;           // temp file for extracted preview model
    private boolean syncingCameraFromCanvas;

    public SettingsDialog(JFrame owner, AppSettings settings) {
        super(owner, get("settings.title"), true);
        this.settings = settings;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(new Dimension(775, 560));
        setMinimumSize(new Dimension(650, 420));
        setResizable(true);
        setLocationRelativeTo(owner);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(get("settings.dataSources"),      buildDataSourcesPanel());
        tabs.addTab(get("settings.theme"),             buildThemePanel());
        tabs.addTab(get("settings.camera"),            buildCameraPanel());
        tabs.addTab(get("settings.externalPrograms"), buildExternalProgramsPanel());
        tabs.addTab(get("settings.tags"),              buildTagsPanel());
        tabs.addTab(get("settings.logs"),              buildLogsPanel());

        setLayout(new BorderLayout(8, 8));
        add(tabs,            BorderLayout.CENTER);
        add(buildButtonBar(), BorderLayout.SOUTH);

        loadFromSettings();
    }

    @Override
    public void dispose() {
        // Clean up GL preview and temp file
        if (cameraPreview != null) {
            cameraPreview = null;
        }
        if (previewTempFile != null) {
            try { java.nio.file.Files.deleteIfExists(previewTempFile); } catch (Exception ignored) {}
            previewTempFile = null;
        }
        super.dispose();
    }

    // ── Panel builders ────────────────────────────────────────────────────────

    private JPanel buildDataSourcesPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 12));
        outer.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));

        // CASC row
        JPanel cascPanel = new JPanel(new GridBagLayout());
        cascPanel.setBorder(BorderFactory.createTitledBorder(get("settings.casc.title")));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        cascPanel.add(new JLabel(get("settings.casc.path")), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        cascPanel.add(cascPathField, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        JButton browseCascBtn = new JButton(get("settings.casc.browse"));
        browseCascBtn.addActionListener(e -> browseCascDirectory());
        cascPanel.add(browseCascBtn, gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.gridwidth = 2;
        JLabel hint = new JLabel("<html><font color='gray'>" + get("settings.casc.hint") + "</font></html>");
        cascPanel.add(hint, gbc);

        // MPQ panel
        JPanel mpqPanel = new JPanel(new BorderLayout(6, 6));
        mpqPanel.setBorder(BorderFactory.createTitledBorder(get("settings.mpq.title")));

        mpqList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        mpqPanel.add(new JScrollPane(mpqList), BorderLayout.CENTER);

        JPanel mpqButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addMpqBtn    = new JButton(get("settings.mpq.add"));
        JButton removeMpqBtn = new JButton(get("settings.mpq.remove"));
        addMpqBtn.addActionListener(e -> addMpqFile());
        removeMpqBtn.addActionListener(e -> removeSelectedMpq());
        mpqButtons.add(addMpqBtn);
        mpqButtons.add(removeMpqBtn);
        mpqPanel.add(mpqButtons, BorderLayout.SOUTH);

        // Data source status label
        JLabel dsStatusLabel = new JLabel();
        int sourceCount = GameDataSource.getInstance().getSourceCount();
        if (sourceCount > 0) {
            dsStatusLabel.setText(fmt("settings.dataSourceStatus", sourceCount));
            dsStatusLabel.setForeground(new Color(60, 140, 60));
        } else {
            dsStatusLabel.setText(get("settings.dataSourceStatusNone"));
            dsStatusLabel.setForeground(new Color(180, 100, 40));
        }
        dsStatusLabel.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));

        JPanel topSection = new JPanel(new BorderLayout(0, 4));
        topSection.add(cascPanel, BorderLayout.CENTER);
        topSection.add(dsStatusLabel, BorderLayout.SOUTH);

        outer.add(topSection, BorderLayout.NORTH);
        outer.add(mpqPanel,  BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildThemePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        panel.add(new JLabel(get("settings.lookAndFeel")), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        themeCombo.addActionListener(e -> {
            ThemeEntry sel = (ThemeEntry) themeCombo.getSelectedItem();
            if (sel != null) applyTheme(sel.className());
        });
        panel.add(themeCombo, gbc);

        // Language row
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(get("settings.language")), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(languageCombo, gbc);

        // Push everything to the top
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), gbc);

        return panel;
    }

    private JPanel buildCameraPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 8));
        outer.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));

        // ── Left: Controls ──
        JPanel controls = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // Description
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2; gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        controls.add(new JLabel("<html>" + get("settings.cameraDesc") + "</html>"), gbc);

        // Angle dials — one per row, label left, dial right
        yawDial.addChangeListener(e -> updateCameraPreview());
        pitchDial.addChangeListener(e -> updateCameraPreview());

        for (var entry : new Object[][]{
                {get("settings.yaw"), yawDial},
                {get("settings.pitch"), pitchDial}}) {
            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.WEST;
            controls.add(new JLabel((String) entry[0]), gbc);
            gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST;
            controls.add((AngleDial) entry[1], gbc);
            row++;
        }

        // Thumbnail animation name
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        controls.add(new JLabel(get("settings.thumbnailAnim")), gbc);
        gbc.gridx = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        animNameField.setColumns(10);
        controls.add(animNameField, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2; gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        controls.add(new JLabel("<html><font color='gray'>" + get("settings.thumbnailAnimHint") + "</font></html>"), gbc);

        // Thumbnail quality
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        controls.add(new JLabel(get("settings.thumbnailQuality")), gbc);
        gbc.gridx = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        qualityCombo.setMaximumSize(new Dimension(160, 26));
        qualityCombo.setPreferredSize(new Dimension(160, 26));
        controls.add(qualityCombo, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2; gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        controls.add(new JLabel("<html><font color='gray'>" + get("settings.thumbnailQualityHint") + "</font></html>"), gbc);

        // Wrap controls in a scroll pane so they don't get clipped
        JScrollPane controlsScroll = new JScrollPane(controls);
        controlsScroll.setBorder(BorderFactory.createEmptyBorder());
        controlsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // ── Right: 3D Preview ──
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBorder(BorderFactory.createTitledBorder(get("settings.preview")));
        previewPanel.setPreferredSize(new Dimension(250, 0));
        JLabel loadingLabel = new JLabel(get("settings.loadingPreview"), JLabel.CENTER);
        previewPanel.add(loadingLabel, BorderLayout.CENTER);

        // Top bar: background color + HD toggle
        bgColorButton.setPreferredSize(new Dimension(80, 26));
        bgColorButton.setBackground(bgColor);
        bgColorButton.setOpaque(true);
        bgColorButton.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(this, get("settings.chooseBgColor"), bgColor);
            if (chosen != null) {
                bgColor = chosen;
                bgColorButton.setBackground(bgColor);
                if (cameraPreview != null) {
                    cameraPreview.setBackgroundColor(String.format("%02X%02X%02X",
                            bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue()));
                }
            }
        });

        JPanel bgColorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bgColorRow.add(new JLabel(get("settings.bgColor")));
        bgColorRow.add(bgColorButton);

        JPanel previewWrapper = new JPanel(new BorderLayout());
        previewWrapper.add(bgColorRow, BorderLayout.NORTH);
        previewWrapper.add(previewPanel, BorderLayout.CENTER);

        // Horizontal split: controls left, preview right
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, controlsScroll, previewWrapper);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerLocation(300);
        splitPane.setContinuousLayout(true);
        outer.add(splitPane, BorderLayout.CENTER);

        loadPreviewModel(previewPanel, loadingLabel);

        return outer;
    }

    private void loadPreviewModel(JPanel previewPanel, JLabel loadingLabel) {
        // Stop and remove current preview
        if (cameraPreview != null) {
            cameraPreview.stopRenderThread();
            previewPanel.remove(cameraPreview);
            cameraPreview = null;
        }
        loadingLabel.setText(Messages.get("settings.loadingPreview"));
        previewPanel.removeAll();
        previewPanel.add(loadingLabel, BorderLayout.CENTER);
        previewPanel.revalidate();
        previewPanel.repaint();

        new SwingWorker<ReterasParsedModel, Void>() {
            Path tempFile;
            @Override protected ReterasParsedModel doInBackground() {
                GameDataSource gds = GameDataSource.getInstance();
                String modelPath = "units\\human\\footman\\footman.mdx";
                tempFile = gds.extractToTemp(modelPath);
                if (tempFile == null) return null;
                previewTempFile = tempFile;
                return ReterasModelParser.parse(tempFile);
            }
            @Override protected void done() {
                try {
                    ReterasParsedModel parsed = get();
                    if (parsed == null || parsed == ReterasParsedModel.EMPTY) {
                        loadingLabel.setText(Messages.get("settings.noPreview"));
                        return;
                    }
                    previewPanel.remove(loadingLabel);
                    cameraPreview = new GlPreviewCanvas(parsed);
                    cameraPreview.setInitialCamera(yawDial.getAngleInt(), pitchDial.getAngleInt());
                    cameraPreview.setCameraOrbitListener(SettingsDialog.this::syncDialsFromCanvas);
                    cameraPreview.setShadingMode(GlPreviewCanvas.ShadingMode.TEXTURED);
                    cameraPreview.setBackgroundColor(String.format("%02X%02X%02X",
                            bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue()));
                    selectStandAnimation(cameraPreview, parsed);
                    cameraPreview.setPlaying(true);
                    previewPanel.add(cameraPreview, BorderLayout.CENTER);
                    previewPanel.revalidate();
                    previewPanel.repaint();
                } catch (Exception ex) {
                    loadingLabel.setText(fmt("settings.previewFailed", ex.getMessage()));
                }
            }
        }.execute();
    }

    private void updateCameraPreview() {
        if (syncingCameraFromCanvas) return;
        if (cameraPreview != null) {
            cameraPreview.setInitialCamera(yawDial.getAngleInt(), pitchDial.getAngleInt());
        }
    }

    /** Called from {@link GlPreviewCanvas} after a mouse-orbit drag. Updates the dials
     *  without re-applying their rounded values back to the canvas mid-drag. */
    private void syncDialsFromCanvas() {
        if (cameraPreview == null) return;
        syncingCameraFromCanvas = true;
        try {
            yawDial.setAngle(cameraPreview.getYawDegrees());
            pitchDial.setAngle(cameraPreview.getPitchDegrees());
        } finally {
            syncingCameraFromCanvas = false;
        }
    }

    private static void selectStandAnimation(GlPreviewCanvas canvas, ReterasParsedModel parsed) {
        var sequences = parsed.animData().sequences();
        for (int i = 0; i < sequences.size(); i++) {
            if (sequences.get(i).name().toLowerCase().contains("stand")) {
                canvas.setSequence(i);
                return;
            }
        }
        if (!sequences.isEmpty()) canvas.setSequence(0);
    }

    private JPanel buildExternalProgramsPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 12));
        outer.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));

        outer.add(new JLabel("<html>" + get("settings.extProg.desc") + "</html>"),
                BorderLayout.NORTH);

        extProgList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        extProgList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof ExternalProgram p) {
                    setText(p.name() + "  —  " + p.command());
                }
                return this;
            }
        });
        outer.add(new JScrollPane(extProgList), BorderLayout.CENTER);

        extProgList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) editSelectedExternalProgram();
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = new JButton(get("settings.extProg.add"));
        JButton editBtn = new JButton(get("settings.extProg.edit"));
        JButton removeBtn = new JButton(get("settings.extProg.remove"));
        addBtn.addActionListener(e -> addExternalProgram());
        editBtn.addActionListener(e -> editSelectedExternalProgram());
        removeBtn.addActionListener(e -> {
            int idx = extProgList.getSelectedIndex();
            if (idx >= 0) extProgListModel.remove(idx);
        });
        buttons.add(addBtn);
        buttons.add(editBtn);
        buttons.add(removeBtn);
        outer.add(buttons, BorderLayout.SOUTH);

        return outer;
    }

    private void addExternalProgram() {
        ExternalProgram result = showExternalProgramDialog(get("settings.extProg.addTitle"), "", "", "");
        if (result != null) {
            extProgListModel.addElement(result);
        }
    }

    private void editSelectedExternalProgram() {
        int idx = extProgList.getSelectedIndex();
        if (idx < 0) return;
        ExternalProgram existing = extProgListModel.get(idx);
        ExternalProgram result = showExternalProgramDialog(get("settings.extProg.editTitle"),
                existing.name(), existing.command(), existing.arguments());
        if (result != null) {
            extProgListModel.set(idx, result);
        }
    }

    private ExternalProgram showExternalProgramDialog(String title, String initialName,
                                                      String initialCmd, String initialArgs) {
        JTextField nameField = new JTextField(initialName, 15);
        JTextField cmdField = new JTextField(initialCmd, 30);
        JTextField argsField = new JTextField(initialArgs, 30);
        JButton browseBtn = new JButton(get("settings.extProg.browse"));
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fc.setFileFilter(new FileNameExtensionFilter(get("settings.extProg.executables"), "exe", "jar", "bat"));
            if (MainWindow.lastChooserDir != null) fc.setCurrentDirectory(MainWindow.lastChooserDir);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                MainWindow.lastChooserDir = fc.getCurrentDirectory();
                cmdField.setText(fc.getSelectedFile().getAbsolutePath());
                if (argsField.getText().isBlank()) argsField.setText("{file}");
                if (nameField.getText().isBlank()) {
                    String fname = fc.getSelectedFile().getName();
                    int dot = fname.lastIndexOf('.');
                    nameField.setText(dot > 0 ? fname.substring(0, dot) : fname);
                }
            }
        });

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel(get("settings.extProg.name")), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(nameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(get("settings.extProg.command")), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(cmdField, gbc);
        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(browseBtn, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(get("settings.extProg.arguments")), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(argsField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("<html><font color='gray'>" + get("settings.extProg.hint") + "</font></html>"), gbc);

        int result = JOptionPane.showConfirmDialog(this, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            String name = nameField.getText().trim();
            String cmd = cmdField.getText().trim();
            String args = argsField.getText().trim();
            if (!name.isEmpty() && !cmd.isEmpty()) {
                return new ExternalProgram(name, cmd, args);
            }
        }
        return null;
    }

    private JPanel buildTagsPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 12));
        outer.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));

        // Enable checkbox + hint
        JPanel topPanel = new JPanel(new BorderLayout(0, 4));
        topPanel.add(tagsEnabledCheckbox, BorderLayout.NORTH);
        JLabel hint = new JLabel("<html><font color='gray'>" + get("settings.tags.enableHint") + "</font></html>");
        topPanel.add(hint, BorderLayout.CENTER);
        outer.add(topPanel, BorderLayout.NORTH);

        // Removed tags list
        JPanel removedPanel = new JPanel(new BorderLayout(6, 6));
        removedPanel.setBorder(BorderFactory.createTitledBorder(get("settings.tags.removedTitle")));
        removedPanel.add(new JLabel("<html><font color='gray'>" + get("settings.tags.removedDesc") + "</font></html>"), BorderLayout.NORTH);
        removedTagsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        removedPanel.add(new JScrollPane(removedTagsList), BorderLayout.CENTER);

        JPanel removedButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton restoreBtn = new JButton(get("settings.tags.restore"));
        restoreBtn.addActionListener(e -> {
            int[] selected = removedTagsList.getSelectedIndices();
            for (int i = selected.length - 1; i >= 0; i--) {
                removedTagsListModel.remove(selected[i]);
            }
        });
        removedButtons.add(restoreBtn);
        removedPanel.add(removedButtons, BorderLayout.SOUTH);

        outer.add(removedPanel, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildLogsPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 8));
        outer.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));

        JLabel desc = new JLabel("<html>" + get("settings.logs.desc") + "</html>");
        outer.add(desc, BorderLayout.NORTH);

        JTextArea logArea = new JTextArea(AppLogBuffer.getText());
        logArea.setEditable(false);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        logArea.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(logArea);
        // Auto-scroll to bottom
        logArea.setCaretPosition(logArea.getDocument().getLength());
        outer.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton copyBtn = new JButton(get("settings.logs.copy"));
        copyBtn.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(logArea.getText()), null);
            JOptionPane.showMessageDialog(this, get("settings.logs.copied"),
                    get("settings.logs"), JOptionPane.INFORMATION_MESSAGE);
        });
        JButton clearBtn = new JButton(get("settings.logs.clear"));
        clearBtn.addActionListener(e -> {
            AppLogBuffer.clear();
            logArea.setText("");
        });
        buttons.add(copyBtn);
        buttons.add(clearBtn);
        outer.add(buttons, BorderLayout.SOUTH);

        return outer;
    }

    private JPanel buildButtonBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));

        JButton applyBtn  = new JButton(get("settings.apply"));
        JButton cancelBtn = new JButton(get("settings.close"));

        applyBtn.addActionListener(e -> applyAndReload());
        cancelBtn.addActionListener(e -> dispose());

        bar.add(applyBtn);
        bar.add(cancelBtn);
        return bar;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void browseCascDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setDialogTitle(get("settings.selectWc3Folder"));
        String current = cascPathField.getText().trim();
        if (!current.isEmpty()) {
            try { chooser.setCurrentDirectory(Path.of(current).toFile()); }
            catch (InvalidPathException ignored) {}
        } else if (MainWindow.lastChooserDir != null) {
            chooser.setCurrentDirectory(MainWindow.lastChooserDir);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            MainWindow.lastChooserDir = chooser.getSelectedFile();
            cascPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void addMpqFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(get("settings.mpqFilter"), "mpq", "w3x", "w3m"));
        if (MainWindow.lastChooserDir != null) chooser.setCurrentDirectory(MainWindow.lastChooserDir);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            MainWindow.lastChooserDir = chooser.getCurrentDirectory();
            for (var file : chooser.getSelectedFiles()) {
                String path = file.getAbsolutePath();
                if (!mpqListModel.contains(path)) {
                    mpqListModel.addElement(path);
                }
            }
        }
    }

    private void removeSelectedMpq() {
        int[] selected = mpqList.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--) {
            mpqListModel.remove(selected[i]);
        }
    }

    private void applyAndReload() {
        String oldCasc = settings.cascPath();
        List<String> oldMpqs = settings.mpqPaths();

        settings.setCascPath(cascPathField.getText().trim());
        List<String> mpqs = new ArrayList<>();
        for (int i = 0; i < mpqListModel.size(); i++) mpqs.add(mpqListModel.get(i));
        settings.setMpqPaths(mpqs);

        boolean dataSourcesChanged = !settings.cascPath().equals(oldCasc)
                || !mpqs.equals(oldMpqs);

        ThemeEntry selected = (ThemeEntry) themeCombo.getSelectedItem();
        if (selected != null) {
            settings.setTheme(selected.className());
            applyTheme(selected.className());
        }

        String hex = String.format("%02X%02X%02X", bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue());
        settings.setBgColor(hex);

        settings.setCameraYaw(yawDial.getAngleInt());
        settings.setCameraPitch(pitchDial.getAngleInt());
        settings.setThumbnailAnimName(animNameField.getText());
        settings.setThumbnailQuality((ThumbnailQuality) qualityCombo.getSelectedItem());

        boolean localeChanged = false;
        LanguageEntry selectedLang = (LanguageEntry) languageCombo.getSelectedItem();
        if (selectedLang != null) {
            localeChanged = !selectedLang.code().equals(settings.locale());
            settings.setLocale(selectedLang.code());
            Messages.setLocale(java.util.Locale.forLanguageTag(selectedLang.code()));
        }

        List<ExternalProgram> programs = new ArrayList<>();
        for (int i = 0; i < extProgListModel.size(); i++) programs.add(extProgListModel.get(i));
        settings.setExternalPrograms(programs);

        settings.setTagsEnabled(tagsEnabledCheckbox.isSelected());
        java.util.Set<String> removedTags = new java.util.LinkedHashSet<>();
        for (int i = 0; i < removedTagsListModel.size(); i++) removedTags.add(removedTagsListModel.get(i));
        settings.setRemovedTags(removedTags);

        settings.save();

        final boolean needsLocaleRefresh = localeChanged;

        // Reload GameDataSource off the EDT to avoid blocking the UI
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                GameDataSource.getInstance().refresh(settings);
                return null;
            }
            @Override protected void done() {
                // Always update data source indicator on main window
                Window owner0 = getOwner();
                if (owner0 instanceof MainWindow mw0) {
                    mw0.updateDataSourceLabel();
                }
                if (dataSourcesChanged) {
                    Window owner = getOwner();
                    if (owner instanceof MainWindow mw) {
                        mw.clearThumbnailCache();
                    }
                }
                if (needsLocaleRefresh) {
                    // Refresh the main window with new translations
                    Window owner = getOwner();
                    if (owner instanceof MainWindow mw) {
                        mw.refreshLocale();
                    }
                    // Dispose and reopen the settings dialog with new locale
                    dispose();
                    SwingUtilities.invokeLater(() -> {
                        SettingsDialog newDialog = new SettingsDialog((JFrame) owner, settings);
                        newDialog.setVisible(true);
                    });
                } else {
                    JOptionPane.showMessageDialog(SettingsDialog.this,
                            Messages.get("settings.applied"), Messages.get("settings.title"), JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }.execute();
    }

    private static void applyTheme(String className) {
        try {
            UIManager.setLookAndFeel(className);
            for (Window w : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(w);
            }
        } catch (Exception ex) {
            System.err.println("[Theme] Failed to apply look and feel: " + ex.getMessage());
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private void loadFromSettings() {
        cascPathField.setText(settings.cascPath());
        mpqListModel.clear();
        for (String p : settings.mpqPaths()) {
            if (p != null && !p.isBlank()) mpqListModel.addElement(p);
        }

        String savedTheme = settings.theme();
        for (int i = 0; i < THEMES.length; i++) {
            if (THEMES[i].className().equals(savedTheme)) {
                themeCombo.setSelectedIndex(i);
                break;
            }
        }

        try {
            bgColor = Color.decode("#" + settings.bgColor());
        } catch (NumberFormatException ignored) {
            bgColor = new Color(0x0F, 0x14, 0x19);
        }
        bgColorButton.setBackground(bgColor);

        yawDial.setAngle(settings.cameraYaw());
        pitchDial.setAngle(settings.cameraPitch());
        animNameField.setText(settings.thumbnailAnimName());
        qualityCombo.setSelectedItem(settings.thumbnailQuality());

        // Select current language
        String savedLocale = settings.locale();
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].code().equals(savedLocale)) {
                languageCombo.setSelectedIndex(i);
                break;
            }
        }

        extProgListModel.clear();
        for (ExternalProgram p : settings.externalPrograms()) {
            extProgListModel.addElement(p);
        }

        tagsEnabledCheckbox.setSelected(settings.tagsEnabled());
        removedTagsListModel.clear();
        for (String tag : settings.removedTags()) {
            removedTagsListModel.addElement(tag);
        }
    }
}
