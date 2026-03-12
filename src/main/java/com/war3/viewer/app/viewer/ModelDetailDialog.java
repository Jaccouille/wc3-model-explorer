package com.war3.viewer.app.viewer;

import com.hiveworkshop.rms.parsers.mdlx.InterpolationType;
import com.hiveworkshop.rms.parsers.mdlx.MdlxAnimatedObject;
import com.hiveworkshop.rms.parsers.mdlx.MdlxAttachment;
import com.hiveworkshop.rms.parsers.mdlx.MdlxBone;
import com.hiveworkshop.rms.parsers.mdlx.MdlxCollisionShape;
import com.hiveworkshop.rms.parsers.mdlx.MdlxCamera;
import com.hiveworkshop.rms.parsers.mdlx.MdlxGeoset;
import com.hiveworkshop.rms.parsers.mdlx.MdlxGeosetAnimation;
import com.hiveworkshop.rms.parsers.mdlx.MdlxModel;
import com.hiveworkshop.rms.parsers.mdlx.MdlxSequence;
import com.hiveworkshop.rms.parsers.mdlx.AnimationMap;
import com.hiveworkshop.rms.parsers.mdlx.timeline.MdlxFloatTimeline;
import com.hiveworkshop.rms.parsers.mdlx.timeline.MdlxTimeline;
import com.hiveworkshop.rms.util.War3ID;
import com.war3.viewer.app.MdxPreviewFactory;
import com.war3.viewer.app.settings.AppSettings;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.DepthTest;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.text.Font;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Transform;
import javafx.scene.input.MouseButton;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class ModelDetailDialog extends Stage {
    private static final double VIEW_W = 800;
    private static final double VIEW_H = 480;
    private static final double DIAG_W = 400;

    private enum ShadingMode { SOLID, TEXTURE, LIT, WIREFRAME }
    private ShadingMode shadingMode = ShadingMode.TEXTURE;

    private ListView<MdxPreviewFactory.TextureDiagEntry> diagList;
    private TextArea  geosetsArea;
    private GridPane  infoGrid;

    // Held for material refresh when team color changes
    private MdlxModel       loadedModel;
    private Path            loadedMdxFile;
    private Path            loadedRootDirectory;
    private MdxPreviewFactory loadedPreviewFactory;
    private List<MdlxGeoset> renderedGeosets = new ArrayList<>();
    private int             teamColorIndex = 0;

    // Orbit / pan state
    private double lastMouseX, lastMouseY;
    private double orbitAzimuth   = 38;
    private double orbitElevation = -18;
    private double cameraDistance = 380;
    private double panX = 0, panY = 0;

    // Animation state
    private BoneAnimator boneAnimator;
    private List<MeshView> meshViews;
    private List<MeshView> edgeMeshViews;
    private List<float[]> bindPoseVertices; // per-geoset bind-pose XYZ in JavaFX space
    private List<MdlxGeoset> geosets;
    /** Maps model.geosets index → meshViews index, for geoset animation lookup. */
    private Map<Integer, Integer> geosetIndexToMeshIndex;
    private AnimationTimer animTimer;
    private long   seqStart, seqEnd;
    private double currentFrame;
    private boolean playing = false;
    private long    lastNanos = -1;
    private double  animSpeed = 1.0;
    private CheckBox loopCheckBox;
    private Button   playBtn;
    private CheckBox bonesCheckBox;
    private CheckBox attachmentsCheckBox;
    private CheckBox labelCheckBox;
    private Canvas   labelCanvas;
    private double   overlayNodeSize = 5.0;
    private final List<Node>     boneLabelNodes      = new ArrayList<>();
    private final List<String>   boneLabelNames      = new ArrayList<>();
    private final List<Integer>  boneLabelObjectIds  = new ArrayList<>();
    private final List<Cylinder> boneLabelCylinders  = new ArrayList<>(); // null = no parent
    private final List<Integer>  boneLabelParentIds  = new ArrayList<>();
    private final List<Node>     attachLabelNodes    = new ArrayList<>();
    private final List<String>   attachLabelNames    = new ArrayList<>();
    private final List<Integer>  attachLabelObjectIds = new ArrayList<>();
    private final List<Cylinder> attachLabelCylinders = new ArrayList<>();
    private final List<Integer>  attachLabelParentIds = new ArrayList<>();

    // Scene extras
    private Group gridGroup;
    private Group overlayGroup;
    private Group extentGroup;
    private Group collisionGroup;
    private Group boneGroup;
    private Group attachmentGroup;

    // Bounds / normalization (set in setupModel, used by camera view)
    private MdxPreviewFactory.BoundsInfo modelBounds;
    private double modelNormScale;

    // Shading lights (toggled per mode)
    private AmbientLight ambientLight;
    private PointLight   pointLight;

    // Cached resolved materials parallel to meshViews (for shading mode swap)
    private final List<javafx.scene.paint.Material> resolvedMaterials = new ArrayList<>();

    // Camera / scene
    private final Group    modelGroup = new Group();
    private final Rotate   azimuthRotate   = new Rotate(38,  Rotate.Y_AXIS);
    private final Rotate   elevationRotate = new Rotate(-18, Rotate.X_AXIS);
    private PerspectiveCamera camera;

    private final Executor bgExec = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "detail-loader");
        t.setDaemon(true);
        return t;
    });

    public ModelDetailDialog(final Window owner, final Path mdxFile, final Path rootDirectory,
                              final MdxPreviewFactory previewFactory) {
        initOwner(owner);
        initModality(Modality.NONE);
        setTitle(mdxFile.getFileName().toString());

        final StackPane viewStack = new StackPane();
        viewStack.setPrefWidth(VIEW_W);

        final ProgressIndicator progress = new ProgressIndicator();
        viewStack.getChildren().add(progress);

        // Animation controls (right panel tab)
        final Label seqLabel = new Label("Animation:");
        final ComboBox<String> seqCombo = new ComboBox<>();
        HBox.setHgrow(seqCombo, Priority.ALWAYS);
        playBtn = new Button("▶ Play");
        final Slider frameSlider = new Slider();
        HBox.setHgrow(frameSlider, Priority.ALWAYS);
        final Label frameLabel = new Label("Frame: 0");

        // Team colour selector
        final Label tcLabel = new Label("Team Color:");
        final ComboBox<String> tcCombo = new ComboBox<>(
                FXCollections.observableArrayList(MdxPreviewFactory.TEAM_COLOR_NAMES));
        tcCombo.getSelectionModel().selectFirst();
        HBox.setHgrow(tcCombo, Priority.ALWAYS);

        final javafx.util.Callback<javafx.scene.control.ListView<String>, ListCell<String>> tcCellFactory =
            lv -> new ListCell<>() {
                private final Rectangle swatch = new Rectangle(14, 14);
                private final HBox cell = new HBox(6, swatch, new Label());
                { cell.setAlignment(Pos.CENTER_LEFT); }
                @Override protected void updateItem(final String name, final boolean empty) {
                    super.updateItem(name, empty);
                    if (empty || name == null) { setGraphic(null); return; }
                    final int idx = tcCombo.getItems().indexOf(name);
                    final Color c = idx >= 0 && idx < MdxPreviewFactory.TEAM_COLORS.length
                            ? MdxPreviewFactory.TEAM_COLORS[idx] : Color.TRANSPARENT;
                    swatch.setFill(c);
                    swatch.setArcWidth(4); swatch.setArcHeight(4);
                    ((Label) cell.getChildren().get(1)).setText(name);
                    setGraphic(cell);
                    setText(null);
                }
            };
        tcCombo.setCellFactory(tcCellFactory);
        tcCombo.setButtonCell(tcCellFactory.call(null));

        // Animation speed
        final Label speedLabel = new Label("Speed:");
        final Slider speedSlider = new Slider(0.1, 2.0, 1.0);
        speedSlider.setMajorTickUnit(1.0);
        speedSlider.setShowTickMarks(true);
        HBox.setHgrow(speedSlider, Priority.ALWAYS);
        final Label speedValueLabel = new Label("1.0×");
        speedSlider.valueProperty().addListener((obs, o, n) -> {
            animSpeed = n.doubleValue();
            speedValueLabel.setText(String.format("%.1f×", n.doubleValue()));
        });

        // Grid toggle
        final CheckBox gridCheckBox = new CheckBox("Grid");
        gridCheckBox.setOnAction(e -> {
            if (gridGroup != null) gridGroup.setVisible(gridCheckBox.isSelected());
        });

        // Loop toggle
        loopCheckBox = new CheckBox("Loop");
        loopCheckBox.setSelected(true);

        // Visualizer toggles
        bonesCheckBox       = new CheckBox("Bones");
        attachmentsCheckBox = new CheckBox("Attachments");
        labelCheckBox       = new CheckBox("Names");
        bonesCheckBox.setOnAction(e -> {
            if (boneGroup != null) boneGroup.setVisible(bonesCheckBox.isSelected());
            redrawLabels();
        });
        attachmentsCheckBox.setOnAction(e -> {
            if (attachmentGroup != null) attachmentGroup.setVisible(attachmentsCheckBox.isSelected());
            redrawLabels();
        });
        labelCheckBox.setOnAction(e -> redrawLabels());

        final HBox visualizerRow = new HBox(10, bonesCheckBox, attachmentsCheckBox, labelCheckBox);
        visualizerRow.setAlignment(Pos.CENTER_LEFT);

        // Overlay node size
        final Label sizeLabel = new Label("Node size:");
        final Slider sizeSlider = new Slider(1.0, 15.0, overlayNodeSize);
        sizeSlider.setPrefWidth(120);
        sizeSlider.valueProperty().addListener((obs, o, n) -> {
            overlayNodeSize = n.doubleValue();
            rebuildBoneAttachOverlays();
        });
        final HBox sizeRow = new HBox(8, sizeLabel, sizeSlider);
        sizeRow.setAlignment(Pos.CENTER_LEFT);

        // Camera view
        final CheckBox cameraCheckBox = new CheckBox("Camera View");
        cameraCheckBox.setDisable(true);

        final HBox seqRow   = new HBox(8, seqLabel, seqCombo);
        seqRow.setAlignment(Pos.CENTER_LEFT);
        final HBox playRow  = new HBox(8, playBtn, frameSlider, frameLabel);
        playRow.setAlignment(Pos.CENTER_LEFT);
        final HBox tcRow    = new HBox(8, tcLabel, tcCombo);
        tcRow.setAlignment(Pos.CENTER_LEFT);
        final HBox speedRow = new HBox(8, speedLabel, speedSlider, speedValueLabel);
        speedRow.setAlignment(Pos.CENTER_LEFT);

        final VBox animControls = new VBox(10, seqRow, playRow, tcRow, speedRow, gridCheckBox, loopCheckBox, visualizerRow, sizeRow, cameraCheckBox);
        animControls.setPadding(new Insets(12));

        // Diagnostic panel — texture list
        diagList = new ListView<>();
        diagList.setCellFactory(lv -> new ListCell<>() {
            private final Rectangle dot      = new Rectangle(8, 8);
            private final Label     idxLabel = new Label();
            private final Label     nameLabel = new Label();
            private final Label     srcBadge = new Label();
            private final HBox      cell;
            {
                dot.setArcWidth(8); dot.setArcHeight(8);
                idxLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
                idxLabel.setMinWidth(30);
                nameLabel.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(nameLabel, Priority.ALWAYS);
                srcBadge.setMinWidth(62); srcBadge.setAlignment(Pos.CENTER);
                cell = new HBox(6, dot, idxLabel, nameLabel, srcBadge);
                cell.setAlignment(Pos.CENTER_LEFT);
                cell.setPadding(new Insets(2, 4, 2, 4));
            }
            @Override
            protected void updateItem(final MdxPreviewFactory.TextureDiagEntry item, final boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                idxLabel.setText("[" + item.index() + "]");
                nameLabel.setText(item.displayName());
                if (item.modelPath() != null && !item.modelPath().isBlank())
                    Tooltip.install(nameLabel, new Tooltip(item.modelPath()));
                final String base = "-fx-font-size: 10px; -fx-padding: 1 5 1 5; -fx-background-radius: 3;";
                switch (item.source()) {
                    case DISK -> {
                        dot.setFill(Color.web("#4caf50"));
                        srcBadge.setText("DISK");
                        srcBadge.setStyle(base + "-fx-background-color:#1b5e20;-fx-text-fill:#a5d6a7;");
                    }
                    case CASC -> {
                        dot.setFill(Color.web("#42a5f5"));
                        srcBadge.setText("CASC");
                        srcBadge.setStyle(base + "-fx-background-color:#0d47a1;-fx-text-fill:#90caf9;");
                    }
                    case MISSING -> {
                        dot.setFill(Color.web("#ef5350"));
                        srcBadge.setText("MISSING");
                        srcBadge.setStyle(base + "-fx-background-color:#b71c1c;-fx-text-fill:#ef9a9a;");
                    }
                    case REPLACEABLE -> {
                        dot.setFill(Color.web("#ffd54f"));
                        srcBadge.setText("REPL");
                        srcBadge.setStyle(base + "-fx-background-color:#e65100;-fx-text-fill:#ffe0b2;");
                    }
                }
                setGraphic(cell);
            }
        });
        diagList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY) {
                final MdxPreviewFactory.TextureDiagEntry sel = diagList.getSelectionModel().getSelectedItem();
                if (sel != null && sel.source() != MdxPreviewFactory.TextureDiagEntry.Source.REPLACEABLE)
                    showTexturePopup(sel);
            }
        });
        VBox.setVgrow(diagList, Priority.ALWAYS);

        final Label texHeader  = new Label("Textures");
        texHeader.setStyle("-fx-font-weight:bold; -fx-padding: 6 8 2 8;");
        final Label geoHeader  = new Label("Geosets");
        geoHeader.setStyle("-fx-font-weight:bold; -fx-padding: 4 8 2 8;");
        geosetsArea = new TextArea();
        geosetsArea.setEditable(false);
        geosetsArea.setWrapText(false);
        geosetsArea.setPrefHeight(130);
        geosetsArea.setMaxHeight(200);
        geosetsArea.getStyleClass().add("diag-area");

        final VBox diagPane = new VBox(texHeader, diagList, geoHeader, geosetsArea);

        // Info tab (populated in setupModel)
        infoGrid = new GridPane();
        infoGrid.setHgap(10);
        infoGrid.setVgap(6);
        infoGrid.setPadding(new Insets(12));
        final ColumnConstraints keyCol = new ColumnConstraints();
        keyCol.setMinWidth(110); keyCol.setPrefWidth(120); keyCol.setMaxWidth(140);
        final ColumnConstraints valCol = new ColumnConstraints();
        valCol.setHgrow(Priority.ALWAYS); valCol.setMinWidth(0); valCol.setFillWidth(true);
        infoGrid.getColumnConstraints().addAll(keyCol, valCol);
        final ScrollPane infoScroll = new ScrollPane(infoGrid);
        infoScroll.setFitToWidth(true);
        infoScroll.setFitToHeight(true);

        // Right panel: three tabs
        final Tab diagTab = new Tab("Texture Diagnostics", diagPane);
        diagTab.setClosable(false);
        final Tab animTab = new Tab("Animation", animControls);
        animTab.setClosable(false);
        final Tab infoTab = new Tab("Info", infoScroll);
        infoTab.setClosable(false);
        final TabPane rightTabPane = new TabPane(animTab, infoTab, diagTab);

        // Horizontal split: 3-D view | right tab pane
        final SplitPane splitPane = new SplitPane(viewStack, rightTabPane);
        splitPane.setDividerPositions(VIEW_W / (VIEW_W + DIAG_W));

        final BorderPane root = new BorderPane();
        root.setCenter(splitPane);

        final Scene scene = new Scene(root, VIEW_W + DIAG_W, VIEW_H);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        setScene(scene);
        setMinWidth(VIEW_W + DIAG_W);
        setMinHeight(600);

        // Wire team-colour picker
        tcCombo.setOnAction(e -> {
            teamColorIndex = tcCombo.getSelectionModel().getSelectedIndex();
            refreshMaterials();
        });

        // Load model in background, then build 3D scene on FX thread
        bgExec.execute(() -> {
            try {
                final MdlxModel model = previewFactory.loadModel(mdxFile);
                Platform.runLater(() -> setupModel(model, mdxFile, rootDirectory,
                        previewFactory, viewStack, seqCombo, playBtn, frameSlider, frameLabel, cameraCheckBox));
            } catch (final Exception e) {
                Platform.runLater(() -> {
                    viewStack.getChildren().setAll(new Label("Failed to load: " + e.getMessage()));
                });
            }
        });

        setOnCloseRequest(e -> stopAnimation());
    }

    // -------------------------------------------------------------------------
    // Model setup
    // -------------------------------------------------------------------------

    private void setupModel(final MdlxModel model,
                             final Path mdxFile, final Path rootDirectory,
                             final MdxPreviewFactory previewFactory,
                             final StackPane viewStack,
                             final ComboBox<String> seqCombo,
                             final Button playBtn,
                             final Slider frameSlider,
                             final Label frameLabel,
                             final CheckBox cameraCheckBox) {
        this.loadedModel          = model;
        this.loadedMdxFile        = mdxFile;
        this.loadedRootDirectory  = rootDirectory;
        this.loadedPreviewFactory = previewFactory;
        this.boneAnimator = new BoneAnimator(model);
        this.geosets       = new ArrayList<>(model.geosets);
        this.meshViews     = new ArrayList<>();
        this.edgeMeshViews = new ArrayList<>();
        this.bindPoseVertices = new ArrayList<>();
        this.renderedGeosets  = new ArrayList<>();
        this.resolvedMaterials.clear();

        MdxPreviewFactory.BoundsInfo bounds = previewFactory.computeBounds(model);
        this.modelBounds = bounds;
        final double boundsMaxDim = !bounds.initialized ? 170.0 :
                Math.max(bounds.maxX - bounds.minX, Math.max(bounds.maxY - bounds.minY, bounds.maxZ - bounds.minZ));
        this.modelNormScale = boundsMaxDim > 0.0001 ? 170.0 / boundsMaxDim : 1.0;

        // Two-pass scene ordering: opaque first (NONE/TRANSPARENT), transparent second (BLEND/ADDITIVE/…).
        // meshViews/renderedGeosets/bindPoseVertices stay in original geoset order for animation.
        final List<MeshView> opaqueMeshViews      = new ArrayList<>();
        final List<MeshView> transparentMeshViews = new ArrayList<>();

        for (int gi = 0; gi < geosets.size(); gi++) {
            final MdlxGeoset geoset = geosets.get(gi);
            if (geoset.lod != 0) continue; // skip lower LOD levels; 0 = HD / only level
            if (geoset.vertices == null || geoset.vertices.length < 9 ||
                    geoset.faces == null || geoset.faces.length < 3) continue;

            // Store bind-pose vertices in JavaFX coordinate space (Y/Z swap)
            final float[] jfxBind = toJfxSpace(geoset.vertices);
            bindPoseVertices.add(jfxBind);

            final TriangleMesh mesh = buildMesh(geoset, jfxBind);
            if (mesh == null) { bindPoseVertices.remove(bindPoseVertices.size() - 1); continue; }

            final MeshView mv = new MeshView(mesh);
            mv.setCullFace(CullFace.NONE);
            final javafx.scene.paint.Material mat = previewFactory.resolveMaterialPublic(model, geoset, mdxFile, rootDirectory, teamColorIndex);
            mv.setMaterial(mat);
            resolvedMaterials.add(mat);
            meshViews.add(mv);
            renderedGeosets.add(geoset);

            // Edge overlay — shares the same TriangleMesh so animation is free
            final MeshView edgeMv = new MeshView(mesh);
            edgeMv.setCullFace(CullFace.NONE);
            edgeMv.setDrawMode(DrawMode.LINE);
            edgeMv.setVisible(false); // hidden except in SOLID mode
            edgeMeshViews.add(edgeMv);

            if (previewFactory.isOpaqueGeoset(geoset, model)) opaqueMeshViews.add(mv);
            else                                               transparentMeshViews.add(mv);
        }

        // Build geoset index → mesh view index mapping for geoset animation lookup
        this.geosetIndexToMeshIndex = new HashMap<>();
        for (int mi = 0; mi < renderedGeosets.size(); mi++) {
            final MdlxGeoset rg = renderedGeosets.get(mi);
            for (int gi = 0; gi < model.geosets.size(); gi++) {
                if (model.geosets.get(gi) == rg) {
                    geosetIndexToMeshIndex.put(gi, mi);
                    break;
                }
            }
        }

        // Compute the normalized bottom-Y of the model (in modelGroup local space) for grid placement
        final double maxDim = !bounds.initialized ? 170.0 :
                Math.max(bounds.maxX - bounds.minX, Math.max(bounds.maxY - bounds.minY, bounds.maxZ - bounds.minZ));
        final double normScale = maxDim > 0.0001 ? 170.0 / maxDim : 1.0;
        final double gridY = !bounds.initialized ? 85.0 : (bounds.maxY - bounds.minY) / 2.0 * normScale + 1.0;

        final Group rawGroup = new Group();
        rawGroup.getChildren().addAll(opaqueMeshViews);
        rawGroup.getChildren().addAll(transparentMeshViews);
        rawGroup.getChildren().addAll(edgeMeshViews);

        if (rawGroup.getChildren().isEmpty()) {
            viewStack.getChildren().setAll(new Label("No renderable geometry found."));
            return;
        }

        // Build overlay groups in a SEPARATE group so toggling visibility never affects rawGroup's
        // layoutBounds and thus never shifts the normalization pivot of the mesh.
        boneLabelNodes.clear();   boneLabelNames.clear();
        attachLabelNodes.clear(); attachLabelNames.clear();
        extentGroup     = buildExtentOverlay(model);
        collisionGroup  = buildCollisionOverlay(model);
        boneGroup       = buildBoneOverlay(model);
        attachmentGroup = buildAttachmentOverlay(model);
        for (final Group og : new Group[]{extentGroup, collisionGroup, boneGroup, attachmentGroup}) {
            og.setVisible(false);
        }
        overlayGroup = new Group(extentGroup, collisionGroup, boneGroup, attachmentGroup);

        previewFactory.normalizeGroup(rawGroup, bounds);
        previewFactory.normalizeGroup(overlayGroup, bounds); // same transform as rawGroup

        gridGroup = buildGrid(gridY);
        gridGroup.setVisible(false);
        modelGroup.getChildren().setAll(rawGroup, overlayGroup, gridGroup);
        modelGroup.getTransforms().setAll(elevationRotate, azimuthRotate);

        // WC3 SD models are pre-lit: the reference shader (simpleDiffuse.frag) has all
        // dynamic lighting commented out — FragColor = texel * geosetColor(1,1,1,1).
        // A single full-white AmbientLight is the exact JavaFX equivalent: no normals needed.
        ambientLight = new AmbientLight(Color.WHITE);

        // Point light used in LIT shading mode (front-right-top, in world space)
        pointLight = new PointLight(Color.WHITE);
        pointLight.setTranslateX(250);
        pointLight.setTranslateY(-300);
        pointLight.setTranslateZ(-200);
        pointLight.setLightOn(false); // off by default (TEXTURE mode)

        Color bgColor;
        try { bgColor = Color.web(AppSettings.get().getViewerBgColor()); }
        catch (final Exception e) { bgColor = Color.web("#1a1e26"); }

        final Group world = new Group(modelGroup, ambientLight, pointLight);
        final SubScene subScene = new SubScene(world, VIEW_W, VIEW_H, true, SceneAntialiasing.BALANCED);
        subScene.setFill(bgColor);
        // Non-managed: excluded from StackPane preferred-size calculation, stays at (0,0)
        subScene.setManaged(false);
        viewStack.widthProperty().addListener((obs, o, n)  -> subScene.setWidth(n.doubleValue()));
        viewStack.heightProperty().addListener((obs, o, n) -> subScene.setHeight(n.doubleValue()));
        // Apply current size immediately in case the dialog is already laid out
        if (viewStack.getWidth()  > 0) subScene.setWidth(viewStack.getWidth());
        if (viewStack.getHeight() > 0) subScene.setHeight(viewStack.getHeight());

        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(9000);
        camera.setFieldOfView(35);
        camera.setTranslateZ(-cameraDistance);
        subScene.setCamera(camera);

        viewStack.getChildren().setAll(subScene);

        // Stats overlay — top-left corner of the 3D view
        final int totalVerts = renderedGeosets.stream().mapToInt(g -> g.vertices.length / 3).sum();
        final int totalTris  = renderedGeosets.stream().mapToInt(g -> g.faces.length  / 3).sum();
        final Label statsLabel = new Label(String.format(
                "Geosets  %d%nVerts    %,d%nPolys    %,d%nBones    %d%nSeqs     %d",
                renderedGeosets.size(), totalVerts, totalTris,
                model.bones.size(), model.sequences.size()));
        statsLabel.setStyle(
                "-fx-background-color: rgba(0,0,0,0.50);" +
                "-fx-text-fill: #a8bdd0;" +
                "-fx-font-family: 'Consolas','Courier New',monospace;" +
                "-fx-font-size: 11px;" +
                "-fx-padding: 5 8 5 8;" +
                "-fx-background-radius: 0 0 4 0;");
        StackPane.setAlignment(statsLabel, Pos.TOP_LEFT);
        viewStack.getChildren().add(statsLabel);

        // Label canvas — transparent 2D overlay for bone/attachment name labels
        labelCanvas = new Canvas(viewStack.getWidth() > 0 ? viewStack.getWidth() : VIEW_W,
                                  viewStack.getHeight() > 0 ? viewStack.getHeight() : VIEW_H);
        labelCanvas.setMouseTransparent(true);
        viewStack.widthProperty().addListener((obs, o, n)  -> labelCanvas.setWidth(n.doubleValue()));
        viewStack.heightProperty().addListener((obs, o, n) -> labelCanvas.setHeight(n.doubleValue()));
        viewStack.getChildren().add(labelCanvas);

        // Shading mode toggle (Blender-style, top-right overlay)
        final ToggleGroup shadingGroup = new ToggleGroup();
        final ToggleButton solidBtn     = new ToggleButton("Solid");
        final ToggleButton textureBtn   = new ToggleButton("Texture");
        final ToggleButton litBtn       = new ToggleButton("Lit");
        final ToggleButton wireframeBtn = new ToggleButton("Wireframe");
        solidBtn.setToggleGroup(shadingGroup);
        textureBtn.setToggleGroup(shadingGroup);
        litBtn.setToggleGroup(shadingGroup);
        wireframeBtn.setToggleGroup(shadingGroup);
        textureBtn.setSelected(true);
        solidBtn.setOnAction(e     -> applyShadingMode(ShadingMode.SOLID));
        textureBtn.setOnAction(e   -> applyShadingMode(ShadingMode.TEXTURE));
        litBtn.setOnAction(e       -> applyShadingMode(ShadingMode.LIT));
        wireframeBtn.setOnAction(e -> applyShadingMode(ShadingMode.WIREFRAME));
        final HBox shadingBar = new HBox(1, wireframeBtn, solidBtn, textureBtn, litBtn);
        shadingBar.setPadding(new Insets(6, 6, 2, 6));

        // Overlay controls row (extents + collision)
        final ComboBox<String> extentCombo = new ComboBox<>();
        extentCombo.getItems().addAll("None", "Box", "Sphere", "Both");
        extentCombo.getSelectionModel().selectFirst();
        extentCombo.setStyle("-fx-font-size: 10px;");
        extentCombo.setPrefWidth(90);
        final CheckBox collisionCheckBox = new CheckBox("Collision");
        collisionCheckBox.setStyle("-fx-font-size: 10px;");
        final HBox overlayBar = new HBox(6, extentCombo, collisionCheckBox);
        overlayBar.setAlignment(Pos.CENTER_LEFT);
        overlayBar.setPadding(new Insets(0, 6, 4, 6));

        final VBox topRightPanel = new VBox(0, shadingBar, overlayBar);
        topRightPanel.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        topRightPanel.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane.setAlignment(topRightPanel, Pos.TOP_RIGHT);
        viewStack.getChildren().add(topRightPanel);

        extentCombo.setOnAction(e -> {
            if (extentGroup == null || extentGroup.getChildren().size() < 2) return;
            final String val = extentCombo.getValue();
            extentGroup.getChildren().get(0).setVisible("Box".equals(val)    || "Both".equals(val));
            extentGroup.getChildren().get(1).setVisible("Sphere".equals(val) || "Both".equals(val));
            extentGroup.setVisible(!"None".equals(val));
        });
        collisionCheckBox.setOnAction(e -> {
            if (collisionGroup != null) collisionGroup.setVisible(collisionCheckBox.isSelected());
        });

        setupOrbit(subScene);

        // Populate sequence list
        final List<String> seqNames = new ArrayList<>();
        seqNames.add("(Bind Pose)");
        for (final MdlxSequence seq : model.sequences) {
            final long bytes = estimateSequenceBytes(model, seq.interval[0], seq.interval[1]);
            final String base = seq.name.isEmpty() ? "(unnamed)" : seq.name;
            seqNames.add(base + "  " + formatDuration(seq) + formatBytes(bytes));
        }
        seqCombo.setItems(FXCollections.observableList(seqNames));

        // Select "Stand" by default (fall back to first sequence)
        int defaultSeqIdx = 0;
        for (int i = 0; i < model.sequences.size(); i++) {
            if (model.sequences.get(i).name.toLowerCase(Locale.ROOT).contains("stand")) {
                defaultSeqIdx = i;
                break;
            }
        }
        if (!model.sequences.isEmpty()) {
            seqCombo.getSelectionModel().select(defaultSeqIdx + 1); // +1 for bind pose entry
            selectSequence(defaultSeqIdx, model, frameSlider, frameLabel);
            playing = true;
            playBtn.setText("⏹ Stop");
            startAnimation(frameSlider, frameLabel);
        } else {
            seqCombo.getSelectionModel().selectFirst();
        }

        seqCombo.setOnAction(e -> {
            stopAnimation();
            final int idx = seqCombo.getSelectionModel().getSelectedIndex() - 1; // -1 for bind pose
            if (idx >= 0 && idx < model.sequences.size()) {
                selectSequence(idx, model, frameSlider, frameLabel);
                playing = true;
                playBtn.setText("⏹ Stop");
                startAnimation(frameSlider, frameLabel);
            } else {
                playing = false;
                playBtn.setText("▶ Play");
                applyBindPose();
            }
        });

        playBtn.setOnAction(e -> {
            final int idx = seqCombo.getSelectionModel().getSelectedIndex() - 1;
            if (idx < 0 || idx >= model.sequences.size()) return;
            if (playing) {
                stopAnimation();
                playBtn.setText("▶ Play");
            } else {
                playing = true;
                playBtn.setText("⏹ Stop");
                startAnimation(frameSlider, frameLabel);
            }
        });

        frameSlider.setOnMouseDragged(e -> {
            if (!playing) {
                currentFrame = frameSlider.getValue();
                frameLabel.setText("Frame: " + (int) currentFrame);
                updateMeshes();
            }
        });

        // Camera view checkbox
        if (!model.cameras.isEmpty()) {
            cameraCheckBox.setDisable(false);
            cameraCheckBox.setOnAction(e -> {
                if (cameraCheckBox.isSelected()) {
                    applyCameraView(model.cameras.get(0));
                } else {
                    restoreOrbitCamera();
                }
                updateMeshes();
            });
        }

        // Populate Info tab
        final int totalGeoVerts = renderedGeosets.stream().mapToInt(g -> g.vertices.length / 3).sum();
        final int totalGeoTris  = renderedGeosets.stream().mapToInt(g -> g.faces.length  / 3).sum();
        final long fileSizeBytes;
        try { fileSizeBytes = java.nio.file.Files.size(mdxFile); } catch (final Exception ex) { /* skip */
            populateDiagnostics(model, mdxFile, rootDirectory, previewFactory);
            return;
        }
        final String formatStr = model.version >= 900 ? "v" + model.version + " (HD/Reforged)"
                                                      : "v" + model.version + " (Classic SD)";
        final String[][] infoRows = {
            { "File",           mdxFile.getFileName().toString() },
            { "Path",           mdxFile.toString() },
            { "Size",           formatFileSize(fileSizeBytes) },
            { "Format",         formatStr },
            { "Geosets",        String.valueOf(renderedGeosets.size()) },
            { "Vertices",       String.format("%,d", totalGeoVerts) },
            { "Triangles",      String.format("%,d", totalGeoTris) },
            { "Bones",          String.valueOf(model.bones.size()) },
            { "Helpers",        String.valueOf(model.helpers.size()) },
            { "Attachments",    String.valueOf(model.attachments.size()) },
            { "Sequences",      String.valueOf(model.sequences.size()) },
            { "Bounding radius",String.format("%.2f", model.extent != null ? model.extent.boundsRadius : 0.0) },
            { "Blend time",     model.blendTime + " ms" },
        };
        infoGrid.getChildren().clear();
        for (int r = 0; r < infoRows.length; r++) {
            final Label key = new Label(infoRows[r][0]);
            key.setStyle("-fx-font-weight: bold; -fx-text-fill: -color-fg-muted;");
            final Label val = new Label(infoRows[r][1]);
            final boolean isPath = "Path".equals(infoRows[r][0]);
            if (isPath) {
                val.setMaxWidth(Double.MAX_VALUE);
                final Tooltip tt = new Tooltip(infoRows[r][1]);
                tt.setWrapText(false);
                Tooltip.install(val, tt);
            } else {
                val.setWrapText(true);
            }
            infoGrid.add(key, 0, r);
            infoGrid.add(val, 1, r);
        }

        // Populate texture diagnostics + geoset summary
        populateDiagnostics(model, mdxFile, rootDirectory, previewFactory);
        redrawLabels();
    }

    private void selectSequence(final int idx, final MdlxModel model,
                                 final Slider frameSlider, final Label frameLabel) {
        final MdlxSequence seq = model.sequences.get(idx);
        seqStart = seq.interval[0];
        seqEnd   = seq.interval[1];
        currentFrame = seqStart;
        frameSlider.setMin(seqStart);
        frameSlider.setMax(seqEnd);
        frameSlider.setValue(seqStart);
        frameLabel.setText("Frame: " + (int) seqStart);
        // NONLOOPING flag (bit 0) means the animation should not loop
        if (loopCheckBox != null) loopCheckBox.setSelected((seq.flags & 1) == 0);
        updateMeshes();
    }

    // -------------------------------------------------------------------------
    // Animation playback
    // -------------------------------------------------------------------------

    private void startAnimation(final Slider frameSlider, final Label frameLabel) {
        lastNanos = -1;
        animTimer = new AnimationTimer() {
            @Override
            public void handle(final long now) {
                if (lastNanos < 0) { lastNanos = now; return; }
                final double elapsed = (now - lastNanos) / 1_000_000.0; // ms
                lastNanos = now;
                currentFrame += elapsed * animSpeed;
                if (currentFrame > seqEnd) {
                    if (loopCheckBox == null || loopCheckBox.isSelected()) {
                        currentFrame = seqStart;
                    } else {
                        currentFrame = seqEnd;
                        stopAnimation();
                        playBtn.setText("▶ Play");
                        return;
                    }
                }
                frameSlider.setValue(currentFrame);
                frameLabel.setText("Frame: " + (int) currentFrame);
                updateMeshes();
                redrawLabels();
            }
        };
        animTimer.start();
    }

    private void stopAnimation() {
        if (animTimer != null) { animTimer.stop(); animTimer = null; }
        lastNanos = -1;
        playing   = false;
    }

    private void updateMeshes() {
        if (boneAnimator == null) return;
        if (boneAnimator.hasBillboardNodes()) {
            boneAnimator.setInverseCameraRotation(computeInverseCameraRotationForModel());
        }
        final long frame = (long) currentFrame;

        // Reset all mesh view opacities to fully visible before applying geoset animations
        for (final MeshView mv : meshViews) mv.setOpacity(1.0);
        if (edgeMeshViews != null) for (final MeshView ev : edgeMeshViews) ev.setOpacity(1.0);

        // Apply geoset animation alpha (visibility)
        if (loadedModel != null && geosetIndexToMeshIndex != null) {
            for (final MdlxGeosetAnimation geosetAnim : loadedModel.geosetAnimations) {
                final Integer mvIdx = geosetIndexToMeshIndex.get(geosetAnim.geosetId);
                if (mvIdx == null) continue;
                final double alpha = sampleGeosetAlpha(geosetAnim, frame, seqStart, seqEnd);
                meshViews.get(mvIdx).setOpacity(alpha);
                if (edgeMeshViews != null && mvIdx < edgeMeshViews.size())
                    edgeMeshViews.get(mvIdx).setOpacity(alpha);
            }
        }

        int bindIdx = 0;
        for (int gi = 0; gi < geosets.size(); gi++) {
            if (bindIdx >= meshViews.size()) break;
            final MdlxGeoset geoset = geosets.get(gi);
            if (geoset.lod != 0) continue; // must match setupModel skip condition
            if (geoset.vertices == null || geoset.vertices.length < 9 ||
                    geoset.faces == null || geoset.faces.length < 3) continue;

            final MeshView mv = meshViews.get(bindIdx);
            final float[] animated = boneAnimator.computeAnimatedVertices(gi, frame, seqStart, seqEnd);
            final float[] jfxAnimated = toJfxSpace(animated);

            ((TriangleMesh) mv.getMesh()).getPoints().setAll(jfxAnimated);
            bindIdx++;
        }
        updateOverlayAnimations(frame);
    }

    private static final War3ID KGAO_ID = AnimationMap.KGAO.getWar3id();

    private static double sampleGeosetAlpha(final MdlxGeosetAnimation geosetAnim,
                                              final long frame, final long seqStart, final long seqEnd) {
        for (final MdlxTimeline<?> tl : geosetAnim.timelines) {
            if (tl.name.equals(KGAO_ID) && tl instanceof MdlxFloatTimeline fat) {
                return sampleFloat1(fat, frame, seqStart, seqEnd);
            }
        }
        return geosetAnim.alpha;
    }

    private static float sampleFloat1(final MdlxFloatTimeline tl, final long frame,
                                       final long seqStart, final long seqEnd) {
        if (tl.frames == null || tl.frames.length == 0) return 1f;

        // If no keyframe falls within the current sequence interval, the geoset is visible
        boolean hasKeyInSeq = false;
        for (final long kf : tl.frames) {
            if (kf >= seqStart && kf <= seqEnd) { hasKeyInSeq = true; break; }
        }
        if (!hasKeyInSeq) return 1f;

        if (frame <= tl.frames[0]) return tl.values[0][0];
        final int last = tl.frames.length - 1;
        if (frame >= tl.frames[last]) return tl.values[last][0];

        int lo = 0, hi = last;
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (tl.frames[mid] <= frame) lo = mid; else hi = mid;
        }

        if (tl.interpolationType == InterpolationType.DONT_INTERP) {
            return tl.values[lo][0];
        }
        final float t = (float) (frame - tl.frames[lo]) / (float) (tl.frames[hi] - tl.frames[lo]);
        return tl.values[lo][0] + t * (tl.values[hi][0] - tl.values[lo][0]);
    }

    private void refreshMaterials() {
        if (loadedModel == null) return;
        resolvedMaterials.clear();
        for (final MdlxGeoset rg : renderedGeosets) {
            resolvedMaterials.add(loadedPreviewFactory.resolveMaterialPublic(
                    loadedModel, rg, loadedMdxFile, loadedRootDirectory, teamColorIndex));
        }
        // Re-apply shading mode so the updated materials are reflected correctly
        if (shadingMode != ShadingMode.SOLID) applyShadingMode(shadingMode);
    }

    private void applyShadingMode(final ShadingMode mode) {
        this.shadingMode = mode;
        if (meshViews == null || meshViews.isEmpty()) return;
        // Restore fill draw mode; wireframe case overrides below
        for (final MeshView mv : meshViews) mv.setDrawMode(DrawMode.FILL);
        // Hide edge overlay by default; SOLID case re-shows it
        final boolean showEdges = (mode == ShadingMode.SOLID);
        if (edgeMeshViews != null) {
            final PhongMaterial edgeMat = new PhongMaterial(Color.web("#202830"));
            edgeMat.setSpecularColor(Color.TRANSPARENT);
            for (final MeshView ev : edgeMeshViews) {
                ev.setVisible(showEdges);
                ev.setMaterial(edgeMat);
            }
        }
        switch (mode) {
            case SOLID -> {
                if (ambientLight != null) ambientLight.setColor(Color.WHITE);
                if (pointLight   != null) pointLight.setLightOn(false);
                for (final MeshView mv : meshViews) {
                    final PhongMaterial mat = new PhongMaterial(Color.web("#b2b2b2"));
                    mat.setSpecularColor(Color.TRANSPARENT);
                    mv.setMaterial(mat);
                }
            }
            case TEXTURE -> {
                if (ambientLight != null) ambientLight.setColor(Color.WHITE);
                if (pointLight   != null) pointLight.setLightOn(false);
                for (int i = 0; i < meshViews.size() && i < resolvedMaterials.size(); i++) {
                    meshViews.get(i).setMaterial(resolvedMaterials.get(i));
                }
            }
            case LIT -> {
                if (ambientLight != null) ambientLight.setColor(Color.gray(0.25));
                if (pointLight   != null) pointLight.setLightOn(true);
                for (int i = 0; i < meshViews.size() && i < resolvedMaterials.size(); i++) {
                    meshViews.get(i).setMaterial(resolvedMaterials.get(i));
                }
            }
            case WIREFRAME -> {
                if (ambientLight != null) ambientLight.setColor(Color.WHITE);
                if (pointLight   != null) pointLight.setLightOn(false);
                final PhongMaterial wireMat = new PhongMaterial(Color.web("#a8c8e8"));
                wireMat.setSpecularColor(Color.TRANSPARENT);
                for (final MeshView mv : meshViews) {
                    mv.setMaterial(wireMat);
                    mv.setDrawMode(DrawMode.LINE);
                }
            }
        }
    }

    private void applyBindPose() {
        if (edgeMeshViews != null) for (final MeshView ev : edgeMeshViews) ev.setOpacity(1.0);
        int bindIdx = 0;
        for (final MeshView mv : meshViews) {
            mv.setOpacity(1.0);
            if (bindIdx < bindPoseVertices.size()) {
                ((TriangleMesh) mv.getMesh()).getPoints().setAll(bindPoseVertices.get(bindIdx));
            }
            bindIdx++;
        }
        resetOverlaysToBind();
    }

    // -------------------------------------------------------------------------
    // Orbit camera
    // -------------------------------------------------------------------------

    private void setupOrbit(final SubScene subScene) {
        subScene.setOnMousePressed(e -> { lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY(); });

        subScene.setOnMouseDragged(e -> {
            final double dx = e.getSceneX() - lastMouseX;
            final double dy = e.getSceneY() - lastMouseY;
            lastMouseX = e.getSceneX();
            lastMouseY = e.getSceneY();
            if (e.getButton() == MouseButton.MIDDLE || e.isShiftDown()) {
                // Pan: translate camera in screen-space, speed proportional to zoom level
                final double speed = cameraDistance * 0.0006;
                panX += dx * speed;
                panY += dy * speed;
                camera.setTranslateX(panX);
                camera.setTranslateY(panY);
            } else {
                orbitAzimuth   = (orbitAzimuth + dx * 0.4) % 360;
                orbitElevation = Math.max(-85, Math.min(85, orbitElevation + dy * 0.3));
                azimuthRotate.setAngle(orbitAzimuth);
                elevationRotate.setAngle(orbitElevation);
                if (boneAnimator != null && boneAnimator.hasBillboardNodes() && !playing) {
                    updateMeshes();
                }
            }
            redrawLabels();
        });

        subScene.setOnScroll(e -> {
            cameraDistance = Math.max(50, Math.min(1500, cameraDistance - e.getDeltaY() * 1.5));
            camera.setTranslateZ(-cameraDistance);
            if (boneAnimator != null && boneAnimator.hasBillboardNodes() && !playing) {
                updateMeshes();
            }
            redrawLabels();
        });

        // Double-right-click resets orbit, pan and zoom
        subScene.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY && e.getClickCount() == 2) resetCamera();
        });
    }

    private void resetCamera() {
        panX = 0; panY = 0;
        orbitAzimuth = 38; orbitElevation = -18; cameraDistance = 380;
        camera.setTranslateX(0); camera.setTranslateY(0); camera.setTranslateZ(-cameraDistance);
        camera.setFieldOfView(35);
        camera.getTransforms().clear();
        azimuthRotate.setAngle(orbitAzimuth);
        elevationRotate.setAngle(orbitElevation);
        modelGroup.getTransforms().setAll(elevationRotate, azimuthRotate);
        redrawLabels();
    }

    private float[] computeInverseCameraRotationForModel() {
        if (camera == null) {
            return new float[]{0f, 0f, 0f, 1f};
        }

        final float[] modelRot = quaternionFromRotates(modelGroup.getTransforms());
        final float[] camRot = quaternionFromRotates(camera.getTransforms());
        final float[] invModel = quatConjugate(modelRot);
        final float[] invCam = quatConjugate(camRot);
        return quatNormalize(quatMultiply(invModel, invCam));
    }

    private static float[] quaternionFromRotates(final List<Transform> transforms) {
        float[] result = new float[]{0f, 0f, 0f, 1f};
        for (final Transform transform : transforms) {
            if (!(transform instanceof Rotate rotate)) {
                continue;
            }
            final double ax = rotate.getAxis().getX();
            final double ay = rotate.getAxis().getY();
            final double az = rotate.getAxis().getZ();
            final double axisLen = Math.sqrt((ax * ax) + (ay * ay) + (az * az));
            if (axisLen < 1e-10) {
                continue;
            }
            final double half = Math.toRadians(rotate.getAngle()) * 0.5;
            final double sin = Math.sin(half) / axisLen;
            final float[] q = new float[]{
                    (float) (ax * sin),
                    (float) (ay * sin),
                    (float) (az * sin),
                    (float) Math.cos(half)
            };
            result = quatNormalize(quatMultiply(result, q));
        }
        return result;
    }

    private static float[] quatMultiply(final float[] a, final float[] b) {
        return new float[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }

    private static float[] quatConjugate(final float[] q) {
        return new float[]{-q[0], -q[1], -q[2], q[3]};
    }

    private static float[] quatNormalize(final float[] q) {
        final float len = (float) Math.sqrt((q[0] * q[0]) + (q[1] * q[1]) + (q[2] * q[2]) + (q[3] * q[3]));
        if (len < 1e-8f) {
            return new float[]{0f, 0f, 0f, 1f};
        }
        final float inv = 1f / len;
        return new float[]{q[0] * inv, q[1] * inv, q[2] * inv, q[3] * inv};
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /** Build a flat ground grid centred at (0, gridY, 0) in modelGroup local space. */
    private static Group buildGrid(final double gridY) {
        final int halfExtent = 220;
        final int mediumStep = 40;
        final int weakStep = Math.max(1, mediumStep / 10);
        final int strongStep = mediumStep * 10;
        final double lineHeight = 0.6;

        final PhongMaterial weakMat = mat(Color.rgb(80, 80, 80, 0.39));
        final PhongMaterial mediumMat = mat(Color.rgb(80, 80, 80, 0.59));
        final PhongMaterial strongMat = mat(Color.rgb(0, 0, 0, 1.0));
        final PhongMaterial xAxisMat = mat(Color.rgb(200, 20, 20, 1.0));
        final PhongMaterial yAxisMat = mat(Color.rgb(20, 200, 20, 1.0));

        final Group group = new Group();

        addGridLayer(group, gridY, halfExtent, weakStep, 0.35, lineHeight, weakMat);
        addGridLayer(group, gridY, halfExtent, mediumStep, 0.65, lineHeight, mediumMat);
        addGridLayer(group, gridY, halfExtent, strongStep, 1.1, lineHeight, strongMat);

        // Axis lines, matching ViewportView.drawGrid intent: X in red, Y in green.
        group.getChildren().add(lineAlongX(halfExtent, 1.6, lineHeight, 0, gridY, xAxisMat));
        group.getChildren().add(lineAlongZ(halfExtent, 1.6, lineHeight, 0, gridY, yAxisMat));

        return group;
    }

    private static void addGridLayer(
            final Group group,
            final double gridY,
            final int halfExtent,
            final int spacing,
            final double thickness,
            final double height,
            final PhongMaterial material
    ) {
        for (int i = -halfExtent; i <= halfExtent; i += spacing) {
            if (i == 0) {
                continue;
            }
            group.getChildren().add(lineAlongX(halfExtent, thickness, height, i, gridY, material));
            group.getChildren().add(lineAlongZ(halfExtent, thickness, height, i, gridY, material));
        }
    }

    private static Box lineAlongX(
            final int halfExtent,
            final double thickness,
            final double height,
            final double z,
            final double y,
            final PhongMaterial material
    ) {
        final Box line = new Box(halfExtent * 2.0, height, thickness);
        line.setTranslateY(y);
        line.setTranslateZ(z);
        line.setMaterial(material);
        return line;
    }

    private static Box lineAlongZ(
            final int halfExtent,
            final double thickness,
            final double height,
            final double x,
            final double y,
            final PhongMaterial material
    ) {
        final Box line = new Box(thickness, height, halfExtent * 2.0);
        line.setTranslateX(x);
        line.setTranslateY(y);
        line.setMaterial(material);
        return line;
    }

    private static PhongMaterial mat(final Color color) {
        final PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(Color.TRANSPARENT);
        return material;
    }

    /** Convert WC3 model-space vertices (Y-up) to JavaFX space (Y-down, Z-depth). */
    private static float[] toJfxSpace(final float[] wc3Vertices) {
        final int n = wc3Vertices.length / 3;
        final float[] out = new float[wc3Vertices.length];
        for (int i = 0; i < n; i++) {
            out[i * 3]     = wc3Vertices[i * 3];
            out[i * 3 + 1] = -wc3Vertices[i * 3 + 2];
            out[i * 3 + 2] = wc3Vertices[i * 3 + 1];
        }
        return out;
    }

    private static TriangleMesh buildMesh(final MdlxGeoset geoset, final float[] jfxPoints) {
        final int vertexCount = jfxPoints.length / 3;
        final float[] texCoords = buildTexCoords(geoset, vertexCount);
        final boolean hasUv = texCoords.length > 2;
        final int uvCount = texCoords.length / 2;

        final List<Integer> faceList = new ArrayList<>();
        for (int i = 0; i + 2 < geoset.faces.length; i += 3) {
            final int a = geoset.faces[i], b = geoset.faces[i + 1], c = geoset.faces[i + 2];
            if (a < 0 || a >= vertexCount || b < 0 || b >= vertexCount || c < 0 || c >= vertexCount) continue;
            faceList.add(a); faceList.add(hasUv && a < uvCount ? a : 0);
            faceList.add(b); faceList.add(hasUv && b < uvCount ? b : 0);
            faceList.add(c); faceList.add(hasUv && c < uvCount ? c : 0);
        }
        if (faceList.isEmpty()) return null;

        final int[] faces = faceList.stream().mapToInt(Integer::intValue).toArray();
        final TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(jfxPoints);
        mesh.getTexCoords().setAll(texCoords);
        mesh.getFaces().setAll(faces);
        return mesh;
    }

    // -------------------------------------------------------------------------
    // Camera view
    // -------------------------------------------------------------------------

    private void applyCameraView(final MdlxCamera wc3Cam) {
        if (!modelBounds.initialized) return;

        final double cx = (modelBounds.minX + modelBounds.maxX) / 2.0;
        final double cy = (modelBounds.minY + modelBounds.maxY) / 2.0;
        final double cz = (modelBounds.minZ + modelBounds.maxZ) / 2.0;

        // Convert WC3 position → JFX space → normalized world space
        final double px = (wc3Cam.position[0] - cx) * modelNormScale;
        final double py = (-wc3Cam.position[2] - cy) * modelNormScale;
        final double pz = (wc3Cam.position[1] - cz) * modelNormScale;

        final double tx = (wc3Cam.targetPosition[0] - cx) * modelNormScale;
        final double ty = (-wc3Cam.targetPosition[2] - cy) * modelNormScale;
        final double tz = (wc3Cam.targetPosition[1] - cz) * modelNormScale;

        // Look direction
        double dx = tx - px, dy = ty - py, dz = tz - pz;
        final double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len > 0.001) { dx /= len; dy /= len; dz /= len; }

        // Decompose look direction into yaw (Y) + pitch (X) Euler angles
        final double horizLen = Math.sqrt(dy*dy + dz*dz);
        final double yaw   = Math.toDegrees(Math.atan2(dx, horizLen));
        final double pitch = horizLen > 0.001 ? Math.toDegrees(Math.atan2(-dy, dz)) : 0.0;

        modelGroup.getTransforms().clear();
        camera.setTranslateX(px);
        camera.setTranslateY(py);
        camera.setTranslateZ(pz);
        if (wc3Cam.fieldOfView > 0) camera.setFieldOfView(Math.toDegrees(wc3Cam.fieldOfView));
        camera.getTransforms().setAll(new Rotate(pitch, Rotate.X_AXIS), new Rotate(yaw, Rotate.Y_AXIS));
    }

    private void restoreOrbitCamera() {
        panX = 0; panY = 0;
        camera.getTransforms().clear();
        camera.setTranslateX(0);
        camera.setTranslateY(0);
        camera.setTranslateZ(-cameraDistance);
        camera.setFieldOfView(35);
        modelGroup.getTransforms().setAll(elevationRotate, azimuthRotate);
    }

    // -------------------------------------------------------------------------
    // Sequence byte estimation
    // -------------------------------------------------------------------------

    private static long estimateSequenceBytes(final MdlxModel model, final long start, final long end) {
        long total = 0;
        final List<MdlxAnimatedObject> objects = new ArrayList<>();
        objects.addAll(model.textureAnimations);
        objects.addAll(model.geosetAnimations);
        objects.addAll(model.bones);
        objects.addAll(model.lights);
        objects.addAll(model.helpers);
        objects.addAll(model.attachments);
        objects.addAll(model.particleEmitters);
        objects.addAll(model.particleEmitters2);
        objects.addAll(model.ribbonEmitters);
        objects.addAll(model.cameras);
        objects.addAll(model.eventObjects);
        objects.addAll(model.collisionShapes);
        for (final MdlxAnimatedObject obj : objects) {
            for (final MdlxTimeline<?> tl : obj.timelines) {
                total += countBytesInRange(tl, start, end);
            }
        }
        return total;
    }

    private static long countBytesInRange(final MdlxTimeline<?> tl, final long start, final long end) {
        if (tl.frames == null || tl.frames.length == 0) return 0;
        int count = 0;
        for (final long frame : tl.frames) {
            if (frame >= start && frame <= end) count++;
        }
        if (count == 0) return 0;
        final long totalBytes = tl.getByteLength();
        final int totalFrames = tl.frames.length;
        return totalFrames > 0 ? (totalBytes - 16) / totalFrames * count : 0;
    }

    private static String formatDuration(final MdlxSequence seq) {
        final long ms = seq.interval[1] - seq.interval[0];
        return String.format("%.1fs  ", ms / 1000.0);
    }

    private static String formatFileSize(final long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

    private static String formatBytes(final long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return "  [" + bytes + " B]";
        return String.format("  [%.1f KB]", bytes / 1024.0);
    }

    private static Group buildExtentOverlay(final MdlxModel model) {
        final Group g = new Group();
        if (model.extent == null) return g;
        final float[] min = model.extent.min;
        final float[] max = model.extent.max;
        if (min == null || max == null || min.length < 3 || max.length < 3) return g;

        // WC3→JavaFX: jfx_x=wc3_x, jfx_y=-wc3_z, jfx_z=wc3_y
        final double cx = (min[0] + max[0]) / 2.0;
        final double cy = -(min[2] + max[2]) / 2.0;
        final double cz = (min[1] + max[1]) / 2.0;
        final double sx = Math.max(0.5, max[0] - min[0]);
        final double sy = Math.max(0.5, max[2] - min[2]);
        final double sz = Math.max(0.5, max[1] - min[1]);

        final Box box = new Box(sx, sy, sz);
        box.setTranslateX(cx); box.setTranslateY(cy); box.setTranslateZ(cz);
        box.setDrawMode(DrawMode.LINE);
        box.setMaterial(mat(Color.rgb(0, 220, 80, 1.0)));

        final Sphere sphere = new Sphere(Math.max(0.5, model.extent.boundsRadius));
        sphere.setTranslateX(cx); sphere.setTranslateY(cy); sphere.setTranslateZ(cz);
        sphere.setDrawMode(DrawMode.LINE);
        sphere.setMaterial(mat(Color.rgb(0, 200, 255, 1.0)));

        g.getChildren().addAll(box, sphere); // index 0=box, 1=sphere (used by extentCombo listener)
        return g;
    }

    private static Group buildCollisionOverlay(final MdlxModel model) {
        final Group g = new Group();
        final PhongMaterial colMat = mat(Color.rgb(255, 140, 0, 1.0));
        for (final MdlxCollisionShape shape : model.collisionShapes) {
            if (shape.vertices == null || shape.vertices.length == 0) continue;
            switch (shape.type) {
                case BOX -> {
                    if (shape.vertices.length < 2) continue;
                    final float[] v0 = shape.vertices[0], v1 = shape.vertices[1];
                    final double minX = Math.min(v0[0], v1[0]), maxX = Math.max(v0[0], v1[0]);
                    final double minY = Math.min(-v0[2], -v1[2]), maxY = Math.max(-v0[2], -v1[2]);
                    final double minZ = Math.min(v0[1], v1[1]), maxZ = Math.max(v0[1], v1[1]);
                    final Box box = new Box(Math.max(0.5, maxX - minX), Math.max(0.5, maxY - minY), Math.max(0.5, maxZ - minZ));
                    box.setTranslateX((minX + maxX) / 2); box.setTranslateY((minY + maxY) / 2); box.setTranslateZ((minZ + maxZ) / 2);
                    box.setDrawMode(DrawMode.LINE); box.setMaterial(colMat);
                    g.getChildren().add(box);
                }
                case SPHERE -> {
                    final float[] v0 = shape.vertices[0];
                    final Sphere sphere = new Sphere(Math.max(0.5, shape.boundsRadius));
                    sphere.setTranslateX(v0[0]); sphere.setTranslateY(-v0[2]); sphere.setTranslateZ(v0[1]);
                    sphere.setDrawMode(DrawMode.LINE); sphere.setMaterial(colMat);
                    g.getChildren().add(sphere);
                }
                case CYLINDER -> {
                    // Approximated as sphere at midpoint
                    if (shape.vertices.length < 2) continue;
                    final float[] v0 = shape.vertices[0], v1 = shape.vertices[1];
                    final Sphere sphere = new Sphere(Math.max(0.5, shape.boundsRadius));
                    sphere.setTranslateX((v0[0] + v1[0]) / 2.0);
                    sphere.setTranslateY((-v0[2] - v1[2]) / 2.0);
                    sphere.setTranslateZ((v0[1] + v1[1]) / 2.0);
                    sphere.setDrawMode(DrawMode.LINE); sphere.setMaterial(colMat);
                    g.getChildren().add(sphere);
                }
                default -> { /* skip PLANE */ }
            }
        }
        return g;
    }

    private Group buildBoneOverlay(final MdlxModel model) {
        final Group g = new Group();
        g.setDepthTest(DepthTest.DISABLE);
        final PhongMaterial boneMat = mat(Color.rgb(255, 220, 0, 1.0));
        final double lineR = Math.max(0.2, overlayNodeSize * 0.12);
        for (final MdlxBone bone : model.bones) {
            final float[] pivot = pivotOf(bone.objectId, model);
            if (pivot == null) continue;
            final Box b = new Box(overlayNodeSize, overlayNodeSize, overlayNodeSize);
            b.setTranslateX(pivot[0]); b.setTranslateY(-pivot[2]); b.setTranslateZ(pivot[1]);
            b.setDrawMode(DrawMode.LINE); b.setMaterial(boneMat);
            g.getChildren().add(b);
            boneLabelNodes.add(b);
            boneLabelNames.add(bone.name.isEmpty() ? "Bone" : bone.name);
            boneLabelObjectIds.add(bone.objectId);
            boneLabelParentIds.add(bone.parentId);
            Cylinder line = null;
            if (bone.parentId >= 0) {
                final float[] pp = pivotOf(bone.parentId, model);
                if (pp != null) {
                    line = lineCylinder(pivot, pp, lineR, boneMat);
                    if (line != null) g.getChildren().add(line);
                }
            }
            boneLabelCylinders.add(line);
        }
        return g;
    }

    private Group buildAttachmentOverlay(final MdlxModel model) {
        final Group g = new Group();
        g.setDepthTest(DepthTest.DISABLE);
        final PhongMaterial attMat = mat(Color.rgb(100, 200, 255, 1.0));
        final double lineR = Math.max(0.2, overlayNodeSize * 0.12);
        for (final MdlxAttachment att : model.attachments) {
            final float[] pivot = pivotOf(att.objectId, model);
            if (pivot == null) continue;
            final Box b = new Box(overlayNodeSize, overlayNodeSize, overlayNodeSize);
            b.setTranslateX(pivot[0]); b.setTranslateY(-pivot[2]); b.setTranslateZ(pivot[1]);
            b.setDrawMode(DrawMode.LINE); b.setMaterial(attMat);
            g.getChildren().add(b);
            attachLabelNodes.add(b);
            attachLabelNames.add(att.name.isEmpty() ? "Attach" : att.name);
            attachLabelObjectIds.add(att.objectId);
            attachLabelParentIds.add(att.parentId);
            Cylinder line = null;
            if (att.parentId >= 0) {
                final float[] pp = pivotOf(att.parentId, model);
                if (pp != null) {
                    line = lineCylinder(pivot, pp, lineR, attMat);
                    if (line != null) g.getChildren().add(line);
                }
            }
            attachLabelCylinders.add(line);
        }
        return g;
    }

    private static float[] pivotOf(final int objectId, final MdlxModel model) {
        if (objectId < 0 || objectId >= model.pivotPoints.size()) return null;
        return model.pivotPoints.get(objectId);
    }

    /** Cylinder between two WC3-space pivot points, oriented along the direction vector. */
    private static Cylinder lineCylinder(final float[] fromWc3, final float[] toWc3,
                                          final double radius, final PhongMaterial mat) {
        final double x1 = fromWc3[0], y1 = -fromWc3[2], z1 = fromWc3[1];
        final double x2 = toWc3[0],   y2 = -toWc3[2],   z2 = toWc3[1];
        final double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        final double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 0.5) return null;
        final Cylinder cyl = new Cylinder(radius, len);
        cyl.setMaterial(mat);
        cyl.setTranslateX((x1 + x2) / 2);
        cyl.setTranslateY((y1 + y2) / 2);
        cyl.setTranslateZ((z1 + z2) / 2);
        final Point3D yAxis = new Point3D(0, 1, 0);
        final Point3D dir   = new Point3D(dx / len, dy / len, dz / len);
        final Point3D axis  = yAxis.crossProduct(dir);
        final double  angle = Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, yAxis.dotProduct(dir)))));
        if (axis.magnitude() > 1e-6) cyl.getTransforms().add(new Rotate(angle, axis));
        return cyl;
    }

    private void rebuildBoneAttachOverlays() {
        if (loadedModel == null || overlayGroup == null) return;
        overlayGroup.getChildren().removeAll(boneGroup, attachmentGroup);
        boneLabelNodes.clear();    boneLabelNames.clear();
        boneLabelObjectIds.clear(); boneLabelCylinders.clear(); boneLabelParentIds.clear();
        attachLabelNodes.clear();  attachLabelNames.clear();
        attachLabelObjectIds.clear(); attachLabelCylinders.clear(); attachLabelParentIds.clear();
        boneGroup       = buildBoneOverlay(loadedModel);
        attachmentGroup = buildAttachmentOverlay(loadedModel);
        boneGroup.setVisible(bonesCheckBox != null && bonesCheckBox.isSelected());
        attachmentGroup.setVisible(attachmentsCheckBox != null && attachmentsCheckBox.isSelected());
        overlayGroup.getChildren().addAll(boneGroup, attachmentGroup);
        redrawLabels();
    }

    private void updateOverlayAnimations(final long frame) {
        if (boneAnimator == null) return;
        final Map<Integer, float[]> worldPos = boneAnimator.computeNodeWorldPositions(frame, seqStart, seqEnd);
        for (int i = 0; i < boneLabelObjectIds.size() && i < boneLabelNodes.size(); i++) {
            final float[] pos = worldPos.get(boneLabelObjectIds.get(i));
            if (pos == null) continue;
            final Node node = boneLabelNodes.get(i);
            node.setTranslateX(pos[0]); node.setTranslateY(-pos[2]); node.setTranslateZ(pos[1]);
            if (i < boneLabelCylinders.size() && i < boneLabelParentIds.size()) {
                final Cylinder cyl = boneLabelCylinders.get(i);
                final int parentId = boneLabelParentIds.get(i);
                if (cyl != null && parentId >= 0) {
                    final float[] pp = worldPos.get(parentId);
                    if (pp != null) updateCylinder(cyl, pos, pp);
                }
            }
        }
        for (int i = 0; i < attachLabelObjectIds.size() && i < attachLabelNodes.size(); i++) {
            final float[] pos = worldPos.get(attachLabelObjectIds.get(i));
            if (pos == null) continue;
            final Node node = attachLabelNodes.get(i);
            node.setTranslateX(pos[0]); node.setTranslateY(-pos[2]); node.setTranslateZ(pos[1]);
            if (i < attachLabelCylinders.size() && i < attachLabelParentIds.size()) {
                final Cylinder cyl = attachLabelCylinders.get(i);
                final int parentId = attachLabelParentIds.get(i);
                if (cyl != null && parentId >= 0) {
                    final float[] pp = worldPos.get(parentId);
                    if (pp != null) updateCylinder(cyl, pos, pp);
                }
            }
        }
    }

    private static void updateCylinder(final Cylinder cyl, final float[] fromWc3, final float[] toWc3) {
        final double x1 = fromWc3[0], y1 = -fromWc3[2], z1 = fromWc3[1];
        final double x2 = toWc3[0],   y2 = -toWc3[2],   z2 = toWc3[1];
        final double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.5) { cyl.setVisible(false); return; }
        cyl.setVisible(true);
        cyl.setHeight(len);
        cyl.setTranslateX((x1 + x2) / 2);
        cyl.setTranslateY((y1 + y2) / 2);
        cyl.setTranslateZ((z1 + z2) / 2);
        final Point3D yAxis = new Point3D(0, 1, 0);
        final Point3D dir   = new Point3D(dx / len, dy / len, dz / len);
        final Point3D axis  = yAxis.crossProduct(dir);
        final double  angle = Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, yAxis.dotProduct(dir)))));
        cyl.getTransforms().clear();
        if (axis.magnitude() > 1e-6) cyl.getTransforms().add(new Rotate(angle, axis));
    }

    private void resetOverlaysToBind() {
        if (loadedModel == null) return;
        for (int i = 0; i < boneLabelObjectIds.size() && i < boneLabelNodes.size(); i++) {
            final float[] pivot = pivotOf(boneLabelObjectIds.get(i), loadedModel);
            if (pivot == null) continue;
            final Node node = boneLabelNodes.get(i);
            node.setTranslateX(pivot[0]); node.setTranslateY(-pivot[2]); node.setTranslateZ(pivot[1]);
            if (i < boneLabelCylinders.size() && i < boneLabelParentIds.size()) {
                final Cylinder cyl = boneLabelCylinders.get(i);
                final int parentId = boneLabelParentIds.get(i);
                if (cyl != null && parentId >= 0) {
                    final float[] pp = pivotOf(parentId, loadedModel);
                    if (pp != null) { cyl.setVisible(true); updateCylinder(cyl, pivot, pp); }
                }
            }
        }
        for (int i = 0; i < attachLabelObjectIds.size() && i < attachLabelNodes.size(); i++) {
            final float[] pivot = pivotOf(attachLabelObjectIds.get(i), loadedModel);
            if (pivot == null) continue;
            final Node node = attachLabelNodes.get(i);
            node.setTranslateX(pivot[0]); node.setTranslateY(-pivot[2]); node.setTranslateZ(pivot[1]);
            if (i < attachLabelCylinders.size() && i < attachLabelParentIds.size()) {
                final Cylinder cyl = attachLabelCylinders.get(i);
                final int parentId = attachLabelParentIds.get(i);
                if (cyl != null && parentId >= 0) {
                    final float[] pp = pivotOf(parentId, loadedModel);
                    if (pp != null) { cyl.setVisible(true); updateCylinder(cyl, pivot, pp); }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Texture diagnostics helpers
    // -------------------------------------------------------------------------

    private void populateDiagnostics(final MdlxModel model, final Path mdxFile,
                                      final Path rootDirectory, final MdxPreviewFactory factory) {
        diagList.setItems(FXCollections.observableList(
                factory.buildTextureDiagList(model, mdxFile, rootDirectory)));
        geosetsArea.setText(buildGeosetText(model));
    }

    private void showTexturePopup(final MdxPreviewFactory.TextureDiagEntry entry) {
        if (loadedPreviewFactory == null) return;
        final Stage popup = new Stage();
        popup.initOwner(this);
        popup.initModality(javafx.stage.Modality.NONE);
        popup.setTitle(entry.displayName());

        final ImageView imgView = new ImageView();
        imgView.setPreserveRatio(true);
        imgView.setSmooth(true);
        imgView.setFitWidth(512);
        imgView.setFitHeight(512);

        final ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(56, 56);
        final StackPane imgPane = new StackPane(spin);
        imgPane.setPrefSize(280, 280);
        imgPane.setStyle("-fx-background-color:#1a1e26;");

        final GridPane infoGrid = new GridPane();
        infoGrid.setHgap(10); infoGrid.setVgap(6);
        infoGrid.setPadding(new Insets(10, 12, 12, 12));
        final ColumnConstraints kc = new ColumnConstraints();
        kc.setMinWidth(80);
        final ColumnConstraints vc = new ColumnConstraints();
        vc.setHgrow(Priority.ALWAYS); vc.setFillWidth(true);
        infoGrid.getColumnConstraints().addAll(kc, vc);

        final Label dimsLabel = addInfoRow(infoGrid, 0, "Dimensions", "loading…");
        addInfoRow(infoGrid, 1, "Format", entry.extension());
        addInfoRow(infoGrid, 2, "Source", entry.source().name());
        final String pathText = entry.resolvedPath() != null ? entry.resolvedPath()
                : (entry.modelPath().isEmpty() ? "(none)" : entry.modelPath() + " — not found");
        final Label pathLabel = addInfoRow(infoGrid, 3, "Path", pathText);
        pathLabel.setWrapText(true);
        if (entry.resolvedPath() != null) {
            final Tooltip tt = new Tooltip(entry.resolvedPath());
            tt.setWrapText(false);
            Tooltip.install(pathLabel, tt);
        }

        final VBox content = new VBox(imgPane, infoGrid);
        content.setPrefWidth(340);
        final javafx.scene.Scene scene = new javafx.scene.Scene(content);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        popup.setScene(scene);
        popup.show();

        bgExec.execute(() -> {
            final Image img = loadedPreviewFactory.loadTextureImage(
                    entry.modelPath(), loadedMdxFile, loadedRootDirectory);
            Platform.runLater(() -> {
                imgView.setImage(img);
                final int w = (int) img.getWidth(), h = (int) img.getHeight();
                dimsLabel.setText(w + " × " + h + " px");
                final double maxSide = Math.min(512, Math.max(w, h));
                imgView.setFitWidth(maxSide);
                imgView.setFitHeight(maxSide);
                imgPane.getChildren().setAll(imgView);
                imgPane.setPrefSize(maxSide, maxSide * h / Math.max(1, w));
                popup.sizeToScene();
            });
        });
    }

    private static Label addInfoRow(final GridPane grid, final int row,
                                     final String key, final String value) {
        final Label k = new Label(key);
        k.setStyle("-fx-font-weight:bold; -fx-text-fill:-color-fg-muted;");
        final Label v = new Label(value);
        v.setMaxWidth(Double.MAX_VALUE);
        grid.add(k, 0, row);
        grid.add(v, 1, row);
        return v;
    }

    private static String buildGeosetText(final MdlxModel model) {
        final StringBuilder sb = new StringBuilder();
        for (int gi = 0; gi < model.geosets.size(); gi++) {
            final MdlxGeoset g = model.geosets.get(gi);
            if (g.lod != 0) continue;
            final int verts = g.vertices == null ? 0 : g.vertices.length / 3;
            final int tris  = g.faces   == null ? 0 : g.faces.length   / 3;
            sb.append(String.format("Geoset %-2d  %5d verts  %5d tris  mat=%d%n",
                    gi, verts, tris, g.materialId));
            final int matIdx = (int) g.materialId;
            if (matIdx >= 0 && matIdx < model.materials.size()) {
                final var mat = model.materials.get(matIdx);
                for (int li = 0; li < mat.layers.size(); li++) {
                    final var layer = mat.layers.get(li);
                    final String texName = layer.textureId >= 0 && layer.textureId < model.textures.size()
                            ? texShortName(model.textures.get(layer.textureId))
                            : "texId=" + layer.textureId;
                    sb.append(String.format("  Layer %d  %-14s  %s%n", li, layer.filterMode, texName));
                }
            }
        }
        return sb.isEmpty() ? "(no renderable geosets)" : sb.toString();
    }

    private static String texShortName(final com.hiveworkshop.rms.parsers.mdlx.MdlxTexture tex) {
        if (tex.replaceableId != 0) return "Replaceable#" + tex.replaceableId;
        final String p = tex.path == null ? "" : tex.path.trim();
        if (p.isEmpty()) return "(empty)";
        final int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private void redrawLabels() {
        if (labelCanvas == null || labelCheckBox == null) return;
        final GraphicsContext gc = labelCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, labelCanvas.getWidth(), labelCanvas.getHeight());
        if (!labelCheckBox.isSelected()) return;
        gc.setFont(Font.font("Consolas", 11));
        gc.setLineWidth(2.5);
        drawLabelSet(gc, boneLabelNodes,   boneLabelNames,   bonesCheckBox);
        drawLabelSet(gc, attachLabelNodes, attachLabelNames, attachmentsCheckBox);
    }

    private void drawLabelSet(final GraphicsContext gc,
                               final List<Node> nodes, final List<String> names,
                               final CheckBox visBox) {
        if (visBox != null && !visBox.isSelected()) return;
        for (int i = 0; i < nodes.size() && i < names.size(); i++) {
            try {
                final javafx.geometry.Bounds sb = nodes.get(i).localToScreen(
                        nodes.get(i).getBoundsInLocal());
                if (sb == null) continue;
                final Point2D cp = labelCanvas.screenToLocal(
                        (sb.getMinX() + sb.getMaxX()) / 2,
                        (sb.getMinY() + sb.getMaxY()) / 2);
                if (cp == null) continue;
                final double x = cp.getX() + 5, y = cp.getY() - 3;
                gc.setStroke(Color.BLACK);
                gc.strokeText(names.get(i), x, y);
                gc.setFill(Color.rgb(255, 235, 100));
                gc.fillText(names.get(i), x, y);
            } catch (final Exception ignored) {}
        }
    }

    private static float[] buildTexCoords(final MdlxGeoset geoset, final int vertexCount) {
        if (geoset.uvSets == null || geoset.uvSets.length == 0 ||
                geoset.uvSets[0] == null || geoset.uvSets[0].length < 2) {
            return new float[]{0f, 0f};
        }
        final float[] src = geoset.uvSets[0];
        final int count = Math.min(vertexCount, src.length / 2);
        if (count <= 0) return new float[]{0f, 0f};
        final float[] uv = new float[count * 2];
        for (int i = 0; i < count; i++) {
            uv[i * 2]     = src[i * 2];
            uv[i * 2 + 1] = src[i * 2 + 1]; // no V-flip: WC3 MDX and JavaFX both use (0,0)=top-left
        }
        return uv;
    }
}
