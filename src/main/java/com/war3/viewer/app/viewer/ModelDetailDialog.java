package com.war3.viewer.app.viewer;

import com.hiveworkshop.rms.parsers.mdlx.InterpolationType;
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
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.shape.Box;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class ModelDetailDialog extends Stage {
    private static final double VIEW_W = 640;
    private static final double VIEW_H = 480;
    private static final double DIAG_W = 400;

    private TextArea diagArea;

    // Held for material refresh when team color changes
    private MdlxModel       loadedModel;
    private Path            loadedMdxFile;
    private Path            loadedRootDirectory;
    private MdxPreviewFactory loadedPreviewFactory;
    private List<MdlxGeoset> renderedGeosets = new ArrayList<>();
    private int             teamColorIndex = 0;

    // Orbit state
    private double lastMouseX, lastMouseY;
    private double orbitAzimuth   = 38;
    private double orbitElevation = -18;
    private double cameraDistance = 380;

    // Animation state
    private BoneAnimator boneAnimator;
    private List<MeshView> meshViews;
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

    // Scene extras
    private Group gridGroup;

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
        viewStack.setPrefSize(VIEW_W, VIEW_H);

        final ProgressIndicator progress = new ProgressIndicator();
        viewStack.getChildren().add(progress);

        // Bottom controls (populated after model loads)
        final Label seqLabel = new Label("Animation:");
        final ComboBox<String> seqCombo = new ComboBox<>();
        seqCombo.setPrefWidth(240);
        final Button playBtn = new Button("▶ Play");
        final Slider frameSlider = new Slider();
        frameSlider.setPrefWidth(220);
        HBox.setHgrow(frameSlider, Priority.ALWAYS);
        final Label frameLabel = new Label("Frame: 0");

        // Team colour selector
        final Label tcLabel = new Label("TC:");
        final ComboBox<String> tcCombo = new ComboBox<>(
                FXCollections.observableArrayList(MdxPreviewFactory.TEAM_COLOR_NAMES));
        tcCombo.getSelectionModel().selectFirst();
        tcCombo.setPrefWidth(120);

        // Animation speed
        final Label speedLabel = new Label("Speed:");
        final Slider speedSlider = new Slider(0.1, 4.0, 1.0);
        speedSlider.setPrefWidth(90);
        speedSlider.setMajorTickUnit(1.0);
        speedSlider.setShowTickMarks(true);
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

        final HBox controls = new HBox(8, seqLabel, seqCombo, playBtn, frameSlider, frameLabel,
                tcLabel, tcCombo, speedLabel, speedSlider, speedValueLabel, gridCheckBox);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(10, 14, 10, 14));
        controls.setStyle("-fx-background-color: #12161e;");

        // Diagnostic panel — width controlled by SplitPane divider
        diagArea = new TextArea("Loading…");
        diagArea.setEditable(false);
        diagArea.setWrapText(false);
        diagArea.setStyle(
                "-fx-font-family: 'Consolas','Courier New',monospace;" +
                "-fx-font-size: 11px;" +
                "-fx-control-inner-background: #0e1118;" +
                "-fx-text-fill: #c8d4e8;");
        VBox.setVgrow(diagArea, Priority.ALWAYS);

        final Label diagHeader = new Label("Texture Diagnostics");
        diagHeader.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 12px;" +
                "-fx-text-fill: #8baac8; -fx-padding: 8 8 4 8;");

        final VBox diagPane = new VBox(diagHeader, diagArea);
        diagPane.setStyle("-fx-background-color: #0e1118;");

        // Horizontal split: 3-D view | diag panel (both resizable)
        final SplitPane splitPane = new SplitPane(viewStack, diagPane);
        splitPane.setDividerPositions(VIEW_W / (VIEW_W + DIAG_W));

        final BorderPane root = new BorderPane();
        root.setCenter(splitPane);
        root.setBottom(controls);
        root.setStyle("-fx-background-color: #1a1e26;");

        final Scene scene = new Scene(root, VIEW_W + DIAG_W, VIEW_H + 56);
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
                        previewFactory, viewStack, seqCombo, playBtn, frameSlider, frameLabel));
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
                             final Label frameLabel) {
        this.loadedModel          = model;
        this.loadedMdxFile        = mdxFile;
        this.loadedRootDirectory  = rootDirectory;
        this.loadedPreviewFactory = previewFactory;
        this.boneAnimator = new BoneAnimator(model);
        this.geosets       = new ArrayList<>(model.geosets);
        this.meshViews     = new ArrayList<>();
        this.bindPoseVertices = new ArrayList<>();
        this.renderedGeosets  = new ArrayList<>();

        MdxPreviewFactory.BoundsInfo bounds = previewFactory.computeBounds(model);

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
            mv.setMaterial(previewFactory.resolveMaterialPublic(model, geoset, mdxFile, rootDirectory, teamColorIndex));
            meshViews.add(mv);
            renderedGeosets.add(geoset);

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

        if (rawGroup.getChildren().isEmpty()) {
            viewStack.getChildren().setAll(new Label("No renderable geometry found."));
            return;
        }

        previewFactory.normalizeGroup(rawGroup, bounds);

        gridGroup = buildGrid(gridY);
        gridGroup.setVisible(false);
        modelGroup.getChildren().setAll(rawGroup, gridGroup);
        modelGroup.getTransforms().setAll(elevationRotate, azimuthRotate);

        // WC3 SD models are pre-lit: the reference shader (simpleDiffuse.frag) has all
        // dynamic lighting commented out — FragColor = texel * geosetColor(1,1,1,1).
        // A single full-white AmbientLight is the exact JavaFX equivalent: no normals needed.
        final AmbientLight ambient = new AmbientLight(Color.WHITE);

        Color bgColor;
        try { bgColor = Color.web(AppSettings.get().getViewerBgColor()); }
        catch (final Exception e) { bgColor = Color.web("#1a1e26"); }

        final Group world = new Group(modelGroup, ambient);
        final SubScene subScene = new SubScene(world, VIEW_W, VIEW_H, true, SceneAntialiasing.BALANCED);
        subScene.setFill(bgColor);

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
                "Geosets: %d   Verts: %,d   Tris: %,d\nBones: %d   Seqs: %d",
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

        setupOrbit(subScene);

        // Populate sequence list
        final List<String> seqNames = new ArrayList<>();
        seqNames.add("(Bind Pose)");
        for (final MdlxSequence seq : model.sequences) {
            seqNames.add(seq.name.isEmpty() ? "(unnamed)" : seq.name);
        }
        seqCombo.setItems(FXCollections.observableList(seqNames));
        seqCombo.getSelectionModel().selectFirst();

        if (!model.sequences.isEmpty()) {
            selectSequence(0, model, frameSlider, frameLabel);
        }

        seqCombo.setOnAction(e -> {
            stopAnimation();
            playBtn.setText("▶ Play");
            playing = false;
            final int idx = seqCombo.getSelectionModel().getSelectedIndex() - 1; // -1 for bind pose
            if (idx >= 0 && idx < model.sequences.size()) {
                selectSequence(idx, model, frameSlider, frameLabel);
            } else {
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

        // Populate diagnostic panel
        diagArea.setText(previewFactory.buildTextureDiagnostics(model, mdxFile, rootDirectory));
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
                if (currentFrame > seqEnd) currentFrame = seqStart;
                frameSlider.setValue(currentFrame);
                frameLabel.setText("Frame: " + (int) currentFrame);
                updateMeshes();
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
        final long frame = (long) currentFrame;

        // Reset all mesh view opacities to fully visible before applying geoset animations
        for (final MeshView mv : meshViews) {
            mv.setOpacity(1.0);
        }

        // Apply geoset animation alpha (visibility)
        if (loadedModel != null && geosetIndexToMeshIndex != null) {
            for (final MdlxGeosetAnimation geosetAnim : loadedModel.geosetAnimations) {
                final Integer mvIdx = geosetIndexToMeshIndex.get(geosetAnim.geosetId);
                if (mvIdx == null) continue;
                final double alpha = sampleGeosetAlpha(geosetAnim, frame);
                meshViews.get(mvIdx).setOpacity(alpha);
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
            final float[] animated = boneAnimator.computeAnimatedVertices(gi, frame);
            final float[] jfxAnimated = toJfxSpace(animated);

            ((TriangleMesh) mv.getMesh()).getPoints().setAll(jfxAnimated);
            bindIdx++;
        }
    }

    private static final War3ID KGAO_ID = AnimationMap.KGAO.getWar3id();

    private static double sampleGeosetAlpha(final MdlxGeosetAnimation geosetAnim, final long frame) {
        for (final MdlxTimeline<?> tl : geosetAnim.timelines) {
            if (tl.name.equals(KGAO_ID) && tl instanceof MdlxFloatTimeline fat) {
                return sampleFloat1(fat, frame);
            }
        }
        return geosetAnim.alpha;
    }

    private static float sampleFloat1(final MdlxFloatTimeline tl, final long frame) {
        if (tl.frames == null || tl.frames.length == 0) return 1f;
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
        for (int i = 0; i < meshViews.size() && i < renderedGeosets.size(); i++) {
            meshViews.get(i).setMaterial(loadedPreviewFactory.resolveMaterialPublic(
                    loadedModel, renderedGeosets.get(i),
                    loadedMdxFile, loadedRootDirectory, teamColorIndex));
        }
    }

    private void applyBindPose() {
        int bindIdx = 0;
        for (final MeshView mv : meshViews) {
            mv.setOpacity(1.0);
            if (bindIdx < bindPoseVertices.size()) {
                ((TriangleMesh) mv.getMesh()).getPoints().setAll(bindPoseVertices.get(bindIdx));
            }
            bindIdx++;
        }
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
            orbitAzimuth   = (orbitAzimuth + dx * 0.4) % 360;
            orbitElevation = Math.max(-85, Math.min(85, orbitElevation + dy * 0.3));
            azimuthRotate.setAngle(orbitAzimuth);
            elevationRotate.setAngle(orbitElevation);
        });

        subScene.setOnScroll(e -> {
            cameraDistance = Math.max(50, Math.min(1500, cameraDistance - e.getDeltaY() * 1.5));
            camera.setTranslateZ(-cameraDistance);
        });
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /** Build a flat ground grid centred at (0, gridY, 0) in modelGroup local space. */
    private static Group buildGrid(final double gridY) {
        final int    HALF    = 200;
        final int    SPACING = 40;
        final double THICK   = 2.0;
        final double HEIGHT  = 1.5;

        final PhongMaterial axisMat = new PhongMaterial(Color.web("#4a5060"));
        axisMat.setSpecularColor(Color.TRANSPARENT);
        final PhongMaterial gridMat = new PhongMaterial(Color.web("#252d3a"));
        gridMat.setSpecularColor(Color.TRANSPARENT);

        final Group g = new Group();
        for (int i = -HALF; i <= HALF; i += SPACING) {
            // Line running along X at Z = i
            final Box lx = new Box(HALF * 2.0, HEIGHT, THICK);
            lx.setTranslateZ(i);
            lx.setTranslateY(gridY);
            lx.setMaterial(i == 0 ? axisMat : gridMat);
            // Line running along Z at X = i
            final Box lz = new Box(THICK, HEIGHT, HALF * 2.0);
            lz.setTranslateX(i);
            lz.setTranslateY(gridY);
            lz.setMaterial(i == 0 ? axisMat : gridMat);
            g.getChildren().addAll(lx, lz);
        }
        return g;
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
