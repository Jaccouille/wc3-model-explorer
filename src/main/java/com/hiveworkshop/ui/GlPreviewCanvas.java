package com.hiveworkshop.ui;

import com.hiveworkshop.model.*;
import com.hiveworkshop.parser.BoneAnimator;
import com.hiveworkshop.parser.GameDataSource;
import com.hiveworkshop.parser.ReterasModelParser;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.opengl.awt.GLData;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public final class GlPreviewCanvas extends AWTGLCanvas {
    private static final float MIN_DISTANCE = 30.0f;
    private static final float MAX_DISTANCE = 3000.0f;

    public enum ShadingMode { SOLID, TEXTURED, LIT, NORMALS, GEOSET_COLORS, BONE_COUNT }

    private final ModelMesh            mesh;
    private final ModelAnimData        animData;
    private final GeosetTexData[]      texData;
    private final CollisionShape[]     collisionShapes;
    private final RibbonEmitterData[]  ribbonEmitters;
    private final MaterialInfo[]       materials;
    private final Path                 modelDir;
    private final Path                 rootDir;
    private volatile ShadingMode  shadingMode = ShadingMode.TEXTURED;

    private float   modelScale   = 1.0f;
    private float   initialYaw   = 200.0f;
    private float   initialPitch = 20.0f;
    private float   yawDegrees   = 200.0f;
    private float   pitchDegrees = 20.0f;
    private Runnable cameraOrbitListener;
    private float   distance     = 300.0f;
    private float   panX         = 0.0f;
    private float   panY         = 0.0f;
    // Computed AABB center used for camera framing (Z-up model space)
    private float   frameCenterX, frameCenterY, frameCenterZ;
    // Cached vertex AABB center (Z-up model space), computed by computeVertexBoundsRadius()
    private float   vertexAABBCenterX, vertexAABBCenterY, vertexAABBCenterZ;
    private boolean wireframe;
    private volatile boolean showExtent;
    private volatile boolean showBones;
    private volatile boolean showHelpers;
    private volatile boolean showAttachments;
    private volatile boolean showRibbonEmitters;
    private volatile boolean showParticleEmitters;
    private volatile boolean showNodeNames;
    private volatile boolean showGrid = true;
    private volatile boolean showCollision;
    private volatile float   nodeSize = 3.0f;
    private volatile int     highlightedBoneId = -1; // objectId of hovered node, -1 = none
    private volatile int     highlightedGeosetIdx = -1; // geoset index to highlight, -1 = none
    private volatile int[]   highlightedGeosetIndices; // multiple geosets to highlight (e.g. for material selection)
    private volatile boolean highlightWireframe = false; // true = wireframe highlight, false = filled overlay
    private volatile float bgR = 0.06f, bgG = 0.08f, bgB = 0.11f;
    private int     lastMouseX, lastMouseY;
    private boolean draggingOrbit, draggingPan;

    // Camera View state
    private volatile boolean       usingCameraView = false;
    private volatile CameraNode    cameraViewNode;
    private float   savedYaw, savedPitch, savedDistance, savedPanX, savedPanY;
    private float   savedFrameCenterX, savedFrameCenterY, savedFrameCenterZ;

    // Render loop – latch signals that the loop has fully exited
    private volatile boolean   renderRunning = false;
    private Thread             renderThread;
    private CountDownLatch     renderStopped;

    // GL resources (all initialised in initGL on the render thread)
    private int solidShader = 0, solidMvp = -1, solidColor = -1, solidAlpha = -1;
    private int texShader   = 0, texMvp   = -1, texMvLoc = -1, texSampler = -1, texHasTex = -1, texAlphaThresh = -1, texAlphaU = -1, texUVTransform = -1, texGeosetColor = -1, texUnshaded = -1;
    private int litShader   = 0, litMvp   = -1, litMvLoc = -1, litSampler = -1, litHasTex = -1, litAlphaThresh = -1, litAlphaU = -1, litUVTransform = -1, litGeosetColor = -1, litUnshaded = -1, litLightDir = -1;
    private int normalsShader = 0, normalsMvp = -1, normalsMvLoc = -1;
    private int vtxColorShader = 0, vtxColorMvp = -1, vtxColorMvLoc = -1;
    private int ribbonShader = 0, ribbonMvp = -1, ribbonSampler = -1, ribbonHasTex = -1, ribbonAlphaU = -1;
    private int particle2Shader = 0, particle2Mvp = -1, particle2Sampler = -1, particle2HasTex = -1;

    private int gridVao = 0, gridVbo = 0, gridVertexCount = 0;

    // Combined VAO for Solid mode
    private int meshVao = 0, meshVbo = 0, meshNormVbo = 0, meshEbo = 0, meshIndexCount = 0;

    // Per-geoset arrays for Textured mode (one entry per mesh-included geoset)
    private int[] geoVao, geoVbo, geoNormVbo, geoUvVbo, geoEbo, geoIndexCount, geoVertCount;
    private int[][] geoTex;       // [geosetIdx][layerIdx] — one texture per material layer
    private int[][] geoLayerUvVbo; // [geosetIdx][layerIdx] — UV VBO per layer (0 = use geoUvVbo)
    private int[]   geosetMaterialId; // [geosetIdx] → material index
    private int[][] geoIndices; // cached index data per geoset (for highlight picking)
    private int[] geoBoneCountVbo;  // per-vertex color VBO for BONE_COUNT mode
    private int highlightEbo = 0; // reusable EBO for bone highlight overlay

    private int cubeVao = 0, cubeVbo = 0, cubeEbo = 0;
    private int nodeCubeVao = 0, nodeCubeVbo = 0, nodeCubeEbo = 0;
    private int extentVao = 0, extentVbo = 0, extentLineCount = 0;
    private int collisionVao = 0, collisionVbo = 0, collisionLineCount = 0;
    private int boneVao = 0, boneVbo = 0;

    // Screenshot request (volatile: written from EDT, completed on render thread)
    private volatile java.util.concurrent.CompletableFuture<BufferedImage> screenshotRequest;

    // Animation (volatile: written from EDT, read from render thread)
    private volatile int     currentSeqIdx = -1;
    private volatile boolean pendingNoSeqPose = false; // request a one-time rest-pose upload
    private volatile boolean animPlaying   = false;
    private volatile float   animSpeed     = 1.0f;
    private volatile long    animTimeMs    = 0L;
    private volatile long    lastNanoNs    = 0L;
    private volatile boolean animLooping  = true;
    private volatile long    globalSeqTimeMs = 0L; // pausable clock for global sequences
    private volatile int     teamColorIdx = 0;
    private volatile boolean tcDirty      = false;
    private float[]          animatedVertices;
    private float[]          animatedNormals;
    private volatile boolean[] geosetVisibility; // per-geoset visibility toggle (null = all visible)
    private float[]          geosetAlphaValues; // per-geoset alpha (sampled from KGAO)
    private float[][]        geosetColorValues; // per-geoset RGB color (sampled from KGAC), null entries = white
    private float[][][]      layerUVTransforms; // [geoset][layer] 4x4 UV transform matrices (null = identity)
    private float[][]        layerAlphaValues;  // [geosetIdx][layerIdx] — animated layer alpha (KMTA)
    private volatile Map<Integer, float[]> lastWorldMap; // cached for node names overlay
    private float lastDtSec; // delta time in seconds from last advanceAnimation()

    // ── Ribbon emitter runtime state ────────────────────────────────────────
    private RibbonState[] ribbonStates;     // one per ribbon emitter, null if no emitters
    private int ribbonVao, ribbonVbo;       // dynamic VAO/VBO for ribbon geometry
    private int[] ribbonTextures;           // GL texture per ribbon emitter (from material)
    private int[] ribbonFilterModes;        // filter mode per ribbon emitter (from material layer)
    private static final int RIBBON_MAX_VERTS = 60000; // max vertices across all ribbons
    private static final int RIBBON_FLOATS_PER_VERT = 8; // x,y,z, u,v, r,g,b

    // ── Particle Emitter 2 runtime state ────────────────────────────────────
    private ParticleEmitter2Data[] particleEmitters2 = ParticleEmitter2Data.EMPTY_ARRAY;
    private Particle2State[] particle2States;
    private int particle2Vao, particle2Vbo;
    private int[] particle2Textures;
    private static final int P2_MAX_VERTS = 120000;
    private static final int P2_FLOATS_PER_VERT = 9; // x,y,z, u,v, r,g,b, a

    // Node names GL overlay (rendered as textured fullscreen quad)
    private int nodeNamesQuadVao, nodeNamesQuadVbo, nodeNamesTex;
    private int nodeNamesTexW, nodeNamesTexH;
    // Cached node positions after updateBoneVbo, for cube drawing
    private float[][] bonePositions, helperPositions, attachPositions, ribbonPositions, particlePositions;

    // ── Shaders ──────────────────────────────────────────────────────────────

    static final String SOLID_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "uniform mat4 mvp;\n" +
        "void main(){ gl_Position = mvp * vec4(aPos,1.0); }\n";

    static final String SOLID_FRAG =
        "#version 330 core\n" +
        "uniform vec3 uColor;\n" +
        "uniform float uAlpha;\n" +
        "out vec4 fragColor;\n" +
        "void main(){ fragColor = vec4(uColor, uAlpha); }\n";

    static final String TEX_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=1) in vec2 aUV;\n" +
        "layout(location=2) in vec3 aNormal;\n" +
        "uniform mat4 mvp;\n" +
        "uniform mat4 uModelView;\n" +
        "uniform mat4 uUVTransform;\n" +
        "out vec2 vUV;\n" +
        "out vec3 vViewNormal;\n" +
        "out vec3 vWorldPos;\n" +
        // WC3 UV: V=0 is top; OpenGL: V=0 is bottom → flip V
        "void main(){\n" +
        "  gl_Position = mvp * vec4(aPos,1.0);\n" +
        "  vec2 flipped = vec2(aUV.x, 1.0-aUV.y);\n" +
        "  vUV = (uUVTransform * vec4(flipped, 0.0, 1.0)).xy;\n" +
        "  vViewNormal = mat3(uModelView) * aNormal;\n" +
        "  vWorldPos = aPos;\n" +
        "}\n";

    // Light direction matching Reteras Model Studio UI/MiscData.txt (model camera mode)
    static final float[] LIGHT_DIR = normalize(0.3f, -0.3f, 0.25f);

    static final String TEX_FRAG =
        "#version 330 core\n" +
        "in vec2 vUV;\n" +
        "in vec3 vViewNormal;\n" +
        "in vec3 vWorldPos;\n" +
        "uniform sampler2D uTex;\n" +
        "uniform bool uHasTex;\n" +
        "uniform float uAlphaThreshold;\n" +
        "uniform float uAlpha;\n" +
        "uniform vec3 uGeosetColor;\n" +
        "uniform bool uUnshaded;\n" +
        "out vec4 fragColor;\n" +
        "void main(){\n" +
        "  vec4 c = uHasTex ? texture(uTex, vUV) : vec4(0.74,0.78,0.86,1.0);\n" +
        "  c.rgb *= uGeosetColor;\n" +
        "  c.a *= uAlpha;\n" +
        "  if(c.a < uAlphaThreshold) discard;\n" +
        "  fragColor = c;\n" +
        "}\n";

    // Lit shader: textured + Retera-style view-space lighting
    static final String LIT_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=1) in vec2 aUV;\n" +
        "layout(location=2) in vec3 aNormal;\n" +
        "uniform mat4 mvp;\n" +
        "uniform mat4 uModelView;\n" +
        "uniform mat4 uUVTransform;\n" +
        "out vec2 vUV;\n" +
        "out vec3 vViewNormal;\n" +
        "out vec3 vWorldPos;\n" +
        "void main(){\n" +
        "  gl_Position = mvp * vec4(aPos,1.0);\n" +
        "  vec2 flipped = vec2(aUV.x, 1.0-aUV.y);\n" +
        "  vUV = (uUVTransform * vec4(flipped, 0.0, 1.0)).xy;\n" +
        "  vViewNormal = mat3(uModelView) * aNormal;\n" +
        "  vWorldPos = aPos;\n" +
        "}\n";

    static final String LIT_FRAG =
        "#version 330 core\n" +
        "in vec2 vUV;\n" +
        "in vec3 vViewNormal;\n" +
        "in vec3 vWorldPos;\n" +
        "uniform sampler2D uTex;\n" +
        "uniform bool uHasTex;\n" +
        "uniform float uAlphaThreshold;\n" +
        "uniform float uAlpha;\n" +
        "uniform vec3 uGeosetColor;\n" +
        "uniform bool uUnshaded;\n" +
        "uniform vec3 uLightDir;\n" +
        "out vec4 fragColor;\n" +
        "void main(){\n" +
        "  vec4 base = uHasTex ? texture(uTex, vUV) : vec4(0.74,0.78,0.86,1.0);\n" +
        "  float shadowThing = 1.0;\n" +
        "  if(!uUnshaded){\n" +
        "    vec3 N = length(vViewNormal) > 0.001 ? normalize(vViewNormal) : normalize(cross(dFdx(vWorldPos), dFdy(vWorldPos)));\n" +
        "    shadowThing = clamp(clamp(dot(N, uLightDir), 0.0, 1.0) + 0.3, 0.0, 1.0);\n" +
        "  }\n" +
        "  base.rgb *= uGeosetColor;\n" +
        "  base.a *= uAlpha;\n" +
        "  if(base.a < uAlphaThreshold) discard;\n" +
        "  fragColor = vec4(base.rgb * shadowThing, base.a);\n" +
        "}\n";

    // Normals shader: visualises vertex normals as RGB colour (N*0.5+0.5)
    static final String NORMALS_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=2) in vec3 aNormal;\n" +
        "uniform mat4 mvp;\n" +
        "uniform mat4 uModelView;\n" +
        "out vec3 vViewNormal;\n" +
        "out vec3 vWorldPos;\n" +
        "void main(){\n" +
        "  gl_Position = mvp * vec4(aPos,1.0);\n" +
        "  vViewNormal = mat3(uModelView) * aNormal;\n" +
        "  vWorldPos = aPos;\n" +
        "}\n";

    static final String NORMALS_FRAG =
        "#version 330 core\n" +
        "in vec3 vViewNormal;\n" +
        "in vec3 vWorldPos;\n" +
        "out vec4 fragColor;\n" +
        "void main(){\n" +
        "  vec3 N = length(vViewNormal) > 0.001 ? normalize(vViewNormal) : normalize(cross(dFdx(vWorldPos), dFdy(vWorldPos)));\n" +
        "  fragColor = vec4(N * 0.5 + 0.5, 1.0);\n" +
        "}\n";

    // Vertex color shader: per-vertex RGB for bone weight visualization
    static final String VTX_COLOR_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=2) in vec3 aNormal;\n" +
        "layout(location=3) in vec3 aColor;\n" +
        "uniform mat4 mvp;\n" +
        "uniform mat4 uModelView;\n" +
        "out vec3 vColor;\n" +
        "out vec3 vViewNormal;\n" +
        "out vec3 vWorldPos;\n" +
        "void main(){\n" +
        "  gl_Position = mvp * vec4(aPos,1.0);\n" +
        "  vColor = aColor;\n" +
        "  vViewNormal = mat3(uModelView) * aNormal;\n" +
        "  vWorldPos = aPos;\n" +
        "}\n";

    static final String VTX_COLOR_FRAG =
        "#version 330 core\n" +
        "in vec3 vColor;\n" +
        "in vec3 vViewNormal;\n" +
        "in vec3 vWorldPos;\n" +
        "out vec4 fragColor;\n" +
        "void main(){\n" +
        "  vec3 N = length(vViewNormal) > 0.001 ? normalize(vViewNormal) : normalize(cross(dFdx(vWorldPos), dFdy(vWorldPos)));\n" +
        "  float shade = clamp(dot(N, vec3(0.0, 0.0, 1.0)) * 0.3 + 0.7, 0.4, 1.0);\n" +
        "  fragColor = vec4(vColor * shade, 1.0);\n" +
        "}\n";

    // Ribbon shader: position + UV + vertex color, textured with alpha blending
    // ── Particle Emitter 2 shader ──────────────────────────────────────────
    static final String PARTICLE2_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=1) in vec2 aUV;\n" +
        "layout(location=2) in vec4 aColor;\n" +
        "uniform mat4 mvp;\n" +
        "out vec2 vUV;\n" +
        "out vec4 vColor;\n" +
        "void main(){\n" +
        "  gl_Position = mvp * vec4(aPos,1.0);\n" +
        "  vUV = vec2(aUV.x, 1.0-aUV.y);\n" +
        "  vColor = aColor;\n" +
        "}\n";

    static final String PARTICLE2_FRAG =
        "#version 330 core\n" +
        "in vec2 vUV;\n" +
        "in vec4 vColor;\n" +
        "uniform sampler2D tex;\n" +
        "uniform bool uHasTex;\n" +
        "out vec4 fragColor;\n" +
        "void main(){\n" +
        "  vec4 texel = uHasTex ? texture(tex, vUV) : vec4(1.0);\n" +
        "  fragColor = vec4(texel.rgb * vColor.rgb, texel.a * vColor.a);\n" +
        "  if (fragColor.a < 0.004) discard;\n" +
        "}\n";

    static final String RIBBON_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=1) in vec2 aUV;\n" +
        "layout(location=2) in vec3 aColor;\n" +
        "uniform mat4 mvp;\n" +
        "out vec2 vUV;\n" +
        "out vec3 vColor;\n" +
        "void main(){\n" +
        "  gl_Position = mvp * vec4(aPos,1.0);\n" +
        "  vUV = vec2(aUV.x, 1.0-aUV.y);\n" +
        "  vColor = aColor;\n" +
        "}\n";

    static final String RIBBON_FRAG =
        "#version 330 core\n" +
        "in vec2 vUV;\n" +
        "in vec3 vColor;\n" +
        "uniform sampler2D tex;\n" +
        "uniform bool uHasTex;\n" +
        "uniform float uAlpha;\n" +
        "out vec4 fragColor;\n" +
        "void main(){\n" +
        "  vec4 texel = uHasTex ? texture(tex, vUV) : vec4(1.0);\n" +
        "  fragColor = vec4(texel.rgb * vColor, texel.a * uAlpha);\n" +
        "  if (fragColor.a < 0.004) discard;\n" +
        "}\n";

    // ── Construction ─────────────────────────────────────────────────────────

    public GlPreviewCanvas(ModelAsset asset) {
        this(ReterasModelParser.parse(asset == null ? null : asset.path()));
    }

    public GlPreviewCanvas(ReterasParsedModel parsed) {
        this(parsed == null ? ModelMesh.EMPTY      : parsed.mesh(),
             parsed == null ? ModelAnimData.EMPTY  : parsed.animData(),
             parsed == null ? new GeosetTexData[0] : parsed.texData(),
             parsed == null ? CollisionShape.EMPTY_ARRAY : parsed.collisionShapes(),
             parsed == null ? RibbonEmitterData.EMPTY_ARRAY : parsed.ribbonEmitters(),
             parsed == null ? ParticleEmitter2Data.EMPTY_ARRAY : parsed.particleEmitters2(),
             parsed == null ? MaterialInfo.EMPTY_ARRAY : parsed.materials(),
             null, null);
    }

    public GlPreviewCanvas(ModelMesh mesh, ModelAnimData animData,
                           GeosetTexData[] texData, Path modelDir) {
        this(mesh, animData, texData, CollisionShape.EMPTY_ARRAY,
                RibbonEmitterData.EMPTY_ARRAY, ParticleEmitter2Data.EMPTY_ARRAY,
                MaterialInfo.EMPTY_ARRAY, modelDir, null);
    }

    public GlPreviewCanvas(ModelMesh mesh, ModelAnimData animData,
                           GeosetTexData[] texData, Path modelDir, Path rootDir) {
        this(mesh, animData, texData, CollisionShape.EMPTY_ARRAY,
                RibbonEmitterData.EMPTY_ARRAY, ParticleEmitter2Data.EMPTY_ARRAY,
                MaterialInfo.EMPTY_ARRAY, modelDir, rootDir);
    }

    public GlPreviewCanvas(ModelMesh mesh, ModelAnimData animData,
                           GeosetTexData[] texData, CollisionShape[] collisionShapes,
                           Path modelDir, Path rootDir) {
        this(mesh, animData, texData, collisionShapes,
                RibbonEmitterData.EMPTY_ARRAY, ParticleEmitter2Data.EMPTY_ARRAY,
                MaterialInfo.EMPTY_ARRAY, modelDir, rootDir);
    }

    public GlPreviewCanvas(ModelMesh mesh, ModelAnimData animData,
                           GeosetTexData[] texData, CollisionShape[] collisionShapes,
                           RibbonEmitterData[] ribbonEmitters, ParticleEmitter2Data[] particleEmitters2,
                           MaterialInfo[] materials, Path modelDir, Path rootDir) {
        super(createGlData());
        this.mesh              = mesh              != null ? mesh              : ModelMesh.EMPTY;
        this.animData          = animData          != null ? animData          : ModelAnimData.EMPTY;
        this.texData           = texData           != null ? texData           : new GeosetTexData[0];
        this.collisionShapes   = collisionShapes   != null ? collisionShapes   : CollisionShape.EMPTY_ARRAY;
        this.ribbonEmitters    = ribbonEmitters     != null ? ribbonEmitters    : RibbonEmitterData.EMPTY_ARRAY;
        this.particleEmitters2 = particleEmitters2  != null ? particleEmitters2 : ParticleEmitter2Data.EMPTY_ARRAY;
        this.materials         = materials          != null ? materials         : MaterialInfo.EMPTY_ARRAY;
        this.modelDir          = modelDir;
        this.rootDir           = rootDir;
        setFocusable(true);
        installInputHandlers();
        applyInitialCameraDistance();
    }

    private static GLData createGlData() {
        GLData d = new GLData();
        d.swapInterval = 1;
        d.doubleBuffer = true;
        d.depthSize    = 24;
        return d;
    }

    // ── GL lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void addNotify() {
        super.addNotify();
        renderStopped = new CountDownLatch(1);
        renderRunning = true;
        renderThread  = new Thread(() -> {
            try {
                while (renderRunning) {
                    try {
                        render();
                    } catch (RuntimeException ex) {
                        // GL context was destroyed (dialog closing) – exit loop cleanly
                        if (renderRunning) System.err.println("[GL-Render] " + ex);
                        break;
                    }
                    try { Thread.sleep(16); } catch (InterruptedException e) { break; }
                }
                // Dispose the GL canvas here, while the GL context is still current and the
                // JAWT drawing surface address is still valid. PlatformWin32GLCanvas.dispose()
                // frees the DS and nulls its stored reference. When removeNotify() later calls
                // disposeCanvas() on the EDT, ds == null and the call is a safe no-op — preventing
                // the JAWT_FreeDrawingSurface(address=0) native crash seen on JDK 25.
                disposeGlCanvas();
            } finally {
                renderStopped.countDown();
            }
        }, "GL-Render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    @Override
    public void initGL() {
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        System.out.println("[GL] " + glGetString(GL_VENDOR) + " / " + glGetString(GL_VERSION));

        compileSolidShader();
        compileTexShader();
        compileLitShader();
        compileNormalsShader();
        compileVtxColorShader();
        buildGridVao();
        if (hasRenderableMesh()) {
            buildMeshVao();
            buildPerGeosetVaos();
            if (animData.hasAnimation()) {
                animatedVertices = mesh.vertices().clone();
                animatedNormals  = mesh.normals().clone();
            }
            // Initialize per-geoset alpha values (1.0 = fully opaque)
            geosetAlphaValues = new float[texData.length];
            java.util.Arrays.fill(geosetAlphaValues, 1.0f);
            geosetColorValues = new float[texData.length][];
            // Initialize with static colors from geoset animations
            for (Map.Entry<Integer, float[]> e : animData.geosetStaticColor().entrySet()) {
                int gi = e.getKey();
                if (gi < geosetColorValues.length) geosetColorValues[gi] = e.getValue().clone();
            }
            layerUVTransforms = new float[texData.length][][];
            for (int gi = 0; gi < texData.length; gi++) {
                int lc = texData[gi].layers().size();
                layerUVTransforms[gi] = new float[Math.max(lc, 1)][];
            }
            // Initialize layer alpha array: [geoset][layer] — use static alpha from layer data
            {
                layerAlphaValues = new float[texData.length][];
                for (int gi = 0; gi < texData.length; gi++) {
                    var layers = texData[gi].layers();
                    int layerCount = layers.size();
                    layerAlphaValues[gi] = new float[layerCount];
                    for (int li = 0; li < layerCount; li++) {
                        layerAlphaValues[gi][li] = layers.get(li).alpha();
                    }
                }
            }
            buildExtentVao();
            buildCollisionVao();
            buildBoneVao();
            buildNodeCubeVao();
            initRibbons();
            initParticles2();
        } else {
            // No mesh geometry — still initialize nodes/particles/ribbons if present
            if (animData.hasAnimation()) {
                buildBoneVao();
                buildNodeCubeVao();
                initRibbons();
                initParticles2();
            } else {
                buildCubeVao();
            }
        }
    }

    @Override
    public void paintGL() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        glViewport(0, 0, w, h);
        glClearColor(bgR, bgG, bgB, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        float[] proj = buildProjection(45f, (float)w/h, 4f, 10000f);
        float[] modelView = buildModelView();
        float[] modelMvp = matMul(proj, modelView);

        // Set default alpha for solid shader (highlight pass changes this)
        if (solidShader != 0) {
            glUseProgram(solidShader);
            glUniform1f(solidAlpha, 1.0f);
            glUseProgram(0);
        }

        // Grid (Z-up model space, XY plane at Z=0)
        if (showGrid && solidShader != 0) {
            glUseProgram(solidShader);
            glUniformMatrix4fv(solidMvp, false, modelMvp);
            glUniform3f(solidColor, 0.23f, 0.27f, 0.31f);
            glBindVertexArray(gridVao);
            glDrawArrays(GL_LINES, 0, gridVertexCount);
            glBindVertexArray(0);
        }

        if (hasRenderableMesh()) {
            if (tcDirty && geoTex != null) { tcDirty = false; reloadTeamColorTextures(); }
            // Update extent overlay if sequence changed
            SequenceInfo extUpd = pendingExtentUpdate;
            if (extUpd != null) {
                pendingExtentUpdate = null;
                if (extUpd.hasExtent()) {
                    updateExtentVao(extUpd.minExtent()[0], extUpd.minExtent()[1], extUpd.minExtent()[2],
                                    extUpd.maxExtent()[0], extUpd.maxExtent()[1], extUpd.maxExtent()[2]);
                } else {
                    updateExtentVao(mesh.minX(), mesh.minY(), mesh.minZ(),
                                    mesh.maxX(), mesh.maxY(), mesh.maxZ());
                }
            }
            if (currentSeqIdx >= 0 && currentSeqIdx < animData.sequences().size()) {
                advanceAnimation();
                if (animData.hasAnimation() && animatedVertices != null) {
                    uploadAnimatedVertices();
                } else if ((ribbonStates != null || particle2States != null) && animData.bones().length > 0) {
                    // Compute world matrices for ribbon/particle emitters even without mesh animation
                    SequenceInfo seq = animData.sequences().get(currentSeqIdx);
                    lastWorldMap = BoneAnimator.computeWorldMatrices(
                            animData.bones(), animTimeMs, seq.start(), seq.end(), animData.globalSequences(), globalSeqTimeMs);
                }
                // Only re-sample geoset properties while animation is playing.
                // When stopped (non-looping, reached end), keep last sampled values
                // so geosets that were hidden at the last frame stay hidden.
                if (animPlaying) {
                    sampleGeosetAlpha();
                    sampleGeosetColor();
                    sampleLayerAlpha();
                    sampleTextureAnims();
                }
                // Freeze ribbon/particle emitters when the animation is stopped/paused.
                if (animPlaying) {
                    simulateRibbons(lastDtSec);
                    simulateParticles2(lastDtSec);
                }
            } else {
                // Even without a selected sequence, global animations should run
                sampleLayerAlpha();
                sampleTextureAnims();
                // Restore the default (rest) pose once when "None" is selected;
                // billboard bones still need a camera-facing refresh every frame.
                if (animatedVertices != null && (pendingNoSeqPose
                        || (animData.bones().length > 0 && hasBillboardBones()))) {
                    uploadAnimatedVerticesNoSequence();
                    pendingNoSeqPose = false;
                }
            }
            if (shadingMode == ShadingMode.GEOSET_COLORS && solidShader != 0 && geoVao != null) {
                drawGeosetColors(modelMvp);
            } else if (shadingMode == ShadingMode.BONE_COUNT && vtxColorShader != 0 && geoVao != null && geoBoneCountVbo != null) {
                drawVertexColors(modelMvp, modelView, geoBoneCountVbo);
            } else if (shadingMode == ShadingMode.NORMALS && normalsShader != 0 && geoVao != null) {
                drawNormals(modelMvp, modelView);
            } else if (shadingMode == ShadingMode.LIT && litShader != 0 && geoVao != null) {
                drawLit(modelMvp, modelView);
            } else if (shadingMode == ShadingMode.TEXTURED && texShader != 0 && geoVao != null) {
                drawTextured(modelMvp, modelView);
            } else if (solidShader != 0 && meshVao != 0) {
                drawSolid(modelMvp);
            }
            // Ribbon emitters (drawn after geosets, before overlays)
            if (ribbonStates != null && ribbonStates.length > 0) {
                drawRibbons(modelMvp);
            }
            // Particle emitters 2 (drawn after ribbons, before overlays)
            if (particle2States != null && particle2States.length > 0) {
                drawParticles2(modelMvp);
            }
            // Highlight overlays (bone hover from Nodes tab, geoset hover from Materials tab)
            if (geoVao != null && solidShader != 0) {
                if (highlightedBoneId >= 0) drawBoneHighlight(modelMvp);
                if (highlightedGeosetIdx >= 0) drawGeosetHighlight(modelMvp);
                if (highlightedGeosetIndices != null) drawMultiGeosetHighlight(modelMvp);
            }
            // Extent overlay (bounding box wireframe)
            if (showExtent && extentVao != 0 && solidShader != 0) {
                glUseProgram(solidShader);
                glUniformMatrix4fv(solidMvp, false, modelMvp);
                glUniform3f(solidColor, 0.2f, 0.9f, 0.4f); // green wireframe
                glBindVertexArray(extentVao);
                glDrawArrays(GL_LINES, 0, extentLineCount);
                glBindVertexArray(0);
                glUseProgram(0);
            }
            // Collision shapes overlay
            if (showCollision && collisionVao != 0 && solidShader != 0) {
                glUseProgram(solidShader);
                glUniformMatrix4fv(solidMvp, false, modelMvp);
                glUniform3f(solidColor, 1.0f, 0.55f, 0.0f); // orange wireframe
                glBindVertexArray(collisionVao);
                glDrawArrays(GL_LINES, 0, collisionLineCount);
                glBindVertexArray(0);
                glUseProgram(0);
            }
            // Node overlays (Bones / Helpers / Attachments)
            boolean anyNodeOverlay = showBones || showHelpers || showAttachments || showRibbonEmitters || showParticleEmitters;
            // Also compute world map when node names are shown (even without visual overlays)
            if (!anyNodeOverlay && showNodeNames && animData.bones().length > 0) {
                updateBoneVbo();
            }
            if (anyNodeOverlay && boneVao != 0 && solidShader != 0 && animData.bones().length > 0) {
                int[] segments = updateBoneVbo();

                glUseProgram(solidShader);
                glUniformMatrix4fv(solidMvp, false, modelMvp);
                glDisable(GL_DEPTH_TEST);

                // Draw parent-child lines
                int totalLines = segments[0] + segments[1] + segments[2] + segments[3] + segments[4];
                if (totalLines > 0) {
                    glBindVertexArray(boneVao);
                    int lineOff = 0;
                    if (showBones && segments[0] > 0) {
                        glUniform3f(solidColor, 1.0f, 0.6f, 0.1f); // orange
                        glDrawArrays(GL_LINES, lineOff, segments[0]);
                    }
                    lineOff += segments[0];
                    if (showHelpers && segments[1] > 0) {
                        glUniform3f(solidColor, 0.9f, 0.85f, 0.2f); // yellow
                        glDrawArrays(GL_LINES, lineOff, segments[1]);
                    }
                    lineOff += segments[1];
                    if (showAttachments && segments[2] > 0) {
                        glUniform3f(solidColor, 0.2f, 0.85f, 0.9f); // cyan
                        glDrawArrays(GL_LINES, lineOff, segments[2]);
                    }
                    lineOff += segments[2];
                    if (showRibbonEmitters && segments[3] > 0) {
                        glUniform3f(solidColor, 0.9f, 0.3f, 0.7f); // magenta
                        glDrawArrays(GL_LINES, lineOff, segments[3]);
                    }
                    lineOff += segments[3];
                    if (showParticleEmitters && segments[4] > 0) {
                        glUniform3f(solidColor, 0.3f, 0.9f, 0.4f); // green
                        glDrawArrays(GL_LINES, lineOff, segments[4]);
                    }
                    glBindVertexArray(0);
                }

                // Draw 3D wireframe cubes at node positions
                if (nodeCubeVao != 0) {
                    glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
                    glBindVertexArray(nodeCubeVao);
                    float ns = nodeSize;
                    if (showBones && bonePositions != null) {
                        glUniform3f(solidColor, 1.0f, 0.8f, 0.2f);
                        drawNodeCubes(bonePositions, modelMvp, ns);
                    }
                    if (showHelpers && helperPositions != null) {
                        glUniform3f(solidColor, 1.0f, 1.0f, 0.4f);
                        drawNodeCubes(helperPositions, modelMvp, ns);
                    }
                    if (showAttachments && attachPositions != null) {
                        glUniform3f(solidColor, 0.4f, 1.0f, 1.0f);
                        drawNodeCubes(attachPositions, modelMvp, ns);
                    }
                    if (showRibbonEmitters && ribbonPositions != null) {
                        glUniform3f(solidColor, 0.9f, 0.3f, 0.7f);
                        drawNodeCubes(ribbonPositions, modelMvp, ns);
                    }
                    if (showParticleEmitters && particlePositions != null) {
                        glUniform3f(solidColor, 0.3f, 0.9f, 0.4f);
                        drawNodeCubes(particlePositions, modelMvp, ns);
                    }
                    glBindVertexArray(0);
                    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
                }

                glEnable(GL_DEPTH_TEST);
                glUseProgram(0);
            }

            // Node names (GL-rendered text overlay)
            if (showNodeNames && animData.bones().length > 0) {
                drawNodeNamesGL(modelMvp, w, h);
            }
        } else if (animData.hasAnimation()) {
            // Mesh-less model with nodes/particles — animate and draw effects
            if (currentSeqIdx >= 0 && currentSeqIdx < animData.sequences().size()) {
                advanceAnimation();
                SequenceInfo seq = animData.sequences().get(currentSeqIdx);
                lastWorldMap = BoneAnimator.computeWorldMatrices(
                        animData.bones(), animTimeMs, seq.start(), seq.end(), animData.globalSequences(), globalSeqTimeMs);
                // Freeze ribbon/particle emitters when the animation is stopped/paused.
                if (animPlaying) {
                    simulateRibbons(lastDtSec);
                    simulateParticles2(lastDtSec);
                }
            }
            // Draw ribbons and particles
            if (ribbonStates != null && ribbonStates.length > 0) {
                drawRibbons(modelMvp);
            }
            if (particle2States != null && particle2States.length > 0) {
                drawParticles2(modelMvp);
            }
            // Draw node overlays
            boolean anyNodeOverlay = showBones || showHelpers || showAttachments || showRibbonEmitters || showParticleEmitters;
            if (anyNodeOverlay && boneVao != 0 && solidShader != 0 && animData.bones().length > 0) {
                int[] segments = updateBoneVbo();
                glUseProgram(solidShader);
                glUniformMatrix4fv(solidMvp, false, modelMvp);
                glDisable(GL_DEPTH_TEST);
                int totalLines = segments[0] + segments[1] + segments[2] + segments[3] + segments[4];
                if (totalLines > 0) {
                    glBindVertexArray(boneVao);
                    int lineOff = 0;
                    if (showBones && segments[0] > 0) {
                        glUniform3f(solidColor, 1.0f, 0.6f, 0.1f);
                        glDrawArrays(GL_LINES, lineOff, segments[0]);
                    }
                    lineOff += segments[0];
                    if (showHelpers && segments[1] > 0) {
                        glUniform3f(solidColor, 0.9f, 0.85f, 0.2f);
                        glDrawArrays(GL_LINES, lineOff, segments[1]);
                    }
                    lineOff += segments[1];
                    if (showAttachments && segments[2] > 0) {
                        glUniform3f(solidColor, 0.2f, 0.85f, 0.9f);
                        glDrawArrays(GL_LINES, lineOff, segments[2]);
                    }
                    lineOff += segments[2];
                    if (showRibbonEmitters && segments[3] > 0) {
                        glUniform3f(solidColor, 0.9f, 0.3f, 0.7f);
                        glDrawArrays(GL_LINES, lineOff, segments[3]);
                    }
                    lineOff += segments[3];
                    if (showParticleEmitters && segments[4] > 0) {
                        glUniform3f(solidColor, 0.3f, 0.9f, 0.4f);
                        glDrawArrays(GL_LINES, lineOff, segments[4]);
                    }
                    glBindVertexArray(0);
                }
                glEnable(GL_DEPTH_TEST);
                glUseProgram(0);
            }
            if (showNodeNames && animData.bones().length > 0) {
                drawNodeNamesGL(modelMvp, w, h);
            }
        } else if (cubeVao != 0 && solidShader != 0) {
            float[] cubeMvp = matMul(proj, buildCameraView());
            glUseProgram(solidShader);
            glUniformMatrix4fv(solidMvp, false, cubeMvp);
            glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);
            glUniform3f(solidColor, 0.74f, 0.78f, 0.86f);
            glBindVertexArray(cubeVao);
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0L);
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            glBindVertexArray(0);
            glUseProgram(0);
        }

        // Screenshot capture (if requested)
        var ssReq = screenshotRequest;
        if (ssReq != null) {
            screenshotRequest = null;
            try {
                int sw = getWidth(), sh = getHeight();
                ByteBuffer pixelBuf = ByteBuffer.allocateDirect(sw * sh * 4).order(java.nio.ByteOrder.nativeOrder());
                glReadPixels(0, 0, sw, sh, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuf);
                BufferedImage img = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < sh; y++) {
                    for (int x = 0; x < sw; x++) {
                        int srcIdx = ((sh - 1 - y) * sw + x) * 4;
                        int r = pixelBuf.get(srcIdx) & 0xFF;
                        int g = pixelBuf.get(srcIdx + 1) & 0xFF;
                        int b = pixelBuf.get(srcIdx + 2) & 0xFF;
                        int a = pixelBuf.get(srcIdx + 3) & 0xFF;
                        img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
                ssReq.complete(img);
            } catch (Exception ex) {
                ssReq.completeExceptionally(ex);
            }
        }

        swapBuffers();
    }

    private void drawSolid(float[] mvp) {
        glUseProgram(solidShader);
        glUniformMatrix4fv(solidMvp, false, mvp);
        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);
        glUniform3f(solidColor, wireframe ? 0.84f : 0.74f, wireframe ? 0.86f : 0.78f, wireframe ? 0.92f : 0.86f);
        glBindVertexArray(meshVao);
        glDrawElements(GL_TRIANGLES, meshIndexCount, GL_UNSIGNED_INT, 0L);

        // Edge overlay: draw wireframe on top of solid fill
        if (!wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            glEnable(GL_POLYGON_OFFSET_LINE);
            glPolygonOffset(-1f, -1f);
            glUniform3f(solidColor, 0.15f, 0.15f, 0.15f);
            glDrawElements(GL_TRIANGLES, meshIndexCount, GL_UNSIGNED_INT, 0L);
            glDisable(GL_POLYGON_OFFSET_LINE);
        }

        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void drawTextured(float[] mvp, float[] mv) {
        drawPerGeoset(mvp, mv, texShader, texMvp, texMvLoc, texSampler, texHasTex, texAlphaThresh, texAlphaU, texUVTransform, texGeosetColor, texUnshaded, -1);
    }

    private void drawLit(float[] mvp, float[] mv) {
        drawPerGeoset(mvp, mv, litShader, litMvp, litMvLoc, litSampler, litHasTex, litAlphaThresh, litAlphaU, litUVTransform, litGeosetColor, litUnshaded, litLightDir);
    }

    private void drawNormals(float[] mvp, float[] mv) {
        glUseProgram(normalsShader);
        glUniformMatrix4fv(normalsMvp, false, mvp);
        if (normalsMvLoc >= 0) glUniformMatrix4fv(normalsMvLoc, false, mv);
        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);
        for (int gi = 0; gi < geoVao.length; gi++) {
            if (geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
            glBindVertexArray(geoVao[gi]);
            glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
        }
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    // Distinct hues for geoset coloring (saturated, evenly spaced around the color wheel)
    private static final float[][] GEOSET_PALETTE = {
        {0.90f, 0.30f, 0.25f}, // red
        {0.25f, 0.75f, 0.40f}, // green
        {0.30f, 0.55f, 0.95f}, // blue
        {0.95f, 0.75f, 0.20f}, // yellow
        {0.80f, 0.35f, 0.85f}, // purple
        {0.20f, 0.85f, 0.85f}, // cyan
        {0.95f, 0.55f, 0.20f}, // orange
        {0.65f, 0.85f, 0.30f}, // lime
        {0.90f, 0.45f, 0.65f}, // pink
        {0.40f, 0.65f, 0.80f}, // steel blue
        {0.75f, 0.60f, 0.35f}, // tan
        {0.55f, 0.80f, 0.65f}, // mint
    };

    private static float[] boneCountColor(int count) {
        return switch (count) {
            case 0 -> new float[]{0.3f, 0.3f, 0.3f};   // grey — no bones
            case 1 -> new float[]{0.2f, 0.4f, 1.0f};    // blue
            case 2 -> new float[]{0.2f, 0.9f, 0.3f};    // green
            case 3 -> new float[]{1.0f, 0.9f, 0.1f};    // yellow
            default -> new float[]{1.0f, 0.15f, 0.1f};  // red (4+)
        };
    }

    private void drawGeosetColors(float[] mvp) {
        glUseProgram(solidShader);
        glUniformMatrix4fv(solidMvp, false, mvp);
        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);

        for (int gi = 0; gi < geoVao.length; gi++) {
            if (geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
            float[] c = GEOSET_PALETTE[gi % GEOSET_PALETTE.length];
            glUniform3f(solidColor, c[0], c[1], c[2]);
            glBindVertexArray(geoVao[gi]);
            glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
        }

        // Edge overlay
        if (!wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            glEnable(GL_POLYGON_OFFSET_LINE);
            glPolygonOffset(-1f, -1f);
            glUniform3f(solidColor, 0.12f, 0.12f, 0.12f);
            for (int gi = 0; gi < geoVao.length; gi++) {
                if (geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
                glBindVertexArray(geoVao[gi]);
                glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
            }
            glDisable(GL_POLYGON_OFFSET_LINE);
        }

        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    /**
     * Draws a semi-transparent overlay on triangles assigned to the highlighted bone.
     * For each geoset, builds a vertex mask based on bone assignment, then re-draws
     * matching triangles (where at least one vertex is influenced by the bone).
     */
    private void drawBoneHighlight(float[] mvp) {
        int boneId = highlightedBoneId;
        if (boneId < 0) return;

        glUseProgram(solidShader);
        glUniformMatrix4fv(solidMvp, false, mvp);
        glUniform3f(solidColor, 0.2f, 0.85f, 1.0f); // bright cyan highlight
        glUniform1f(solidAlpha, 0.45f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        // Slight offset to avoid z-fighting
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1f, -1f);

        java.util.List<GeosetSkinData> skins = animData.geosets();
        int gi = 0;
        for (GeosetSkinData skin : skins) {
            if (skin.vertexCount() == 0) continue;
            if (gi >= geoVao.length || geoVao[gi] == 0) { gi++; continue; }

            // Build vertex mask: which vertices are influenced by this bone?
            boolean[] vertMask = new boolean[skin.vertexCount()];
            boolean anyMatch = false;
            if (skin.hasSkinning()) {
                int[] vg = skin.vertexGroup();
                int[][] groups = skin.groupBoneObjectIds();
                for (int vi = 0; vi < skin.vertexCount(); vi++) {
                    int grp = (vg != null && vi < vg.length) ? vg[vi] : 0;
                    if (grp < groups.length) {
                        for (int bid : groups[grp]) {
                            if (bid == boneId) { vertMask[vi] = true; anyMatch = true; break; }
                        }
                    }
                }
            }
            if (!anyMatch) { gi++; continue; }

            // Use cached index data to filter triangles
            int[] indices = (geoIndices != null && gi < geoIndices.length) ? geoIndices[gi] : null;
            if (indices == null) { gi++; continue; }
            int idxCount = indices.length;

            // Filter triangles
            int[] highlighted = new int[idxCount];
            int hCount = 0;
            for (int t = 0; t + 2 < idxCount; t += 3) {
                int i0 = indices[t], i1 = indices[t+1], i2 = indices[t+2];
                if ((i0 < vertMask.length && vertMask[i0])
                 || (i1 < vertMask.length && vertMask[i1])
                 || (i2 < vertMask.length && vertMask[i2])) {
                    highlighted[hCount++] = i0;
                    highlighted[hCount++] = i1;
                    highlighted[hCount++] = i2;
                }
            }

            if (hCount > 0) {
                // Ensure reusable highlight EBO exists
                if (highlightEbo == 0) highlightEbo = glGenBuffers();
                // Copy to a correctly-sized array for glBufferData(int[])
                int[] trimmed = new int[hCount];
                System.arraycopy(highlighted, 0, trimmed, 0, hCount);
                glBindVertexArray(geoVao[gi]);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, highlightEbo);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, trimmed, GL_STREAM_DRAW);
                glDrawElements(GL_TRIANGLES, hCount, GL_UNSIGNED_INT, 0L);
                // Restore original EBO on the VAO
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, geoEbo[gi]);
            }

            gi++;
        }

        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glUniform1f(solidAlpha, 1.0f);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void drawVertexColors(float[] mvp, float[] mv, int[] colorVbo) {
        if (vtxColorShader == 0) return;
        glUseProgram(vtxColorShader);
        glUniformMatrix4fv(vtxColorMvp, false, mvp);
        if (vtxColorMvLoc >= 0) glUniformMatrix4fv(vtxColorMvLoc, false, mv);
        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);

        for (int gi = 0; gi < geoVao.length; gi++) {
            if (geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
            glBindVertexArray(geoVao[gi]);
            if (colorVbo[gi] != 0) {
                glBindBuffer(GL_ARRAY_BUFFER, colorVbo[gi]);
                glVertexAttribPointer(3, 3, GL_FLOAT, false, 12, 0L);
                glEnableVertexAttribArray(3);
            } else {
                glVertexAttrib3f(3, 0.5f, 0.5f, 0.5f); // grey fallback
                glDisableVertexAttribArray(3);
            }
            glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
        }
        glBindVertexArray(0);
        glUseProgram(0);
    }

    /** Draws a highlight on the entire highlighted geoset (overlay or wireframe). */
    private void drawGeosetHighlight(float[] mvp) {
        int gi = highlightedGeosetIdx;
        if (gi < 0 || gi >= geoVao.length || geoVao[gi] == 0 || geoIndexCount[gi] == 0) return;

        glUseProgram(solidShader);
        glUniformMatrix4fv(solidMvp, false, mvp);
        glUniform3f(solidColor, 1.0f, 0.6f, 0.15f); // warm orange highlight
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindVertexArray(geoVao[gi]);

        if (highlightWireframe) {
            glUniform1f(solidAlpha, 1.0f);
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            glLineWidth(2.0f);
            glEnable(GL_POLYGON_OFFSET_LINE);
            glPolygonOffset(-1f, -1f);
            glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
            glDisable(GL_POLYGON_OFFSET_LINE);
            glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);
        } else {
            glUniform1f(solidAlpha, 0.45f);
            glDepthMask(false);
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(-1f, -1f);
            glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
            glDisable(GL_POLYGON_OFFSET_FILL);
            glDepthMask(true);
        }

        glDisable(GL_BLEND);
        glUniform1f(solidAlpha, 1.0f);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void drawMultiGeosetHighlight(float[] mvp) {
        int[] indices = highlightedGeosetIndices;
        if (indices == null || indices.length == 0) return;

        glUseProgram(solidShader);
        glUniformMatrix4fv(solidMvp, false, mvp);
        glUniform3f(solidColor, 1.0f, 0.6f, 0.15f);
        glUniform1f(solidAlpha, 0.45f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1f, -1f);

        for (int gi : indices) {
            if (gi < 0 || gi >= geoVao.length || geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
            glBindVertexArray(geoVao[gi]);
            glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
        }

        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glUniform1f(solidAlpha, 1.0f);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    /**
     * Two-pass per-geoset draw with filter mode GL state.
     * Pass 1: opaque geosets (NONE, TRANSPARENT) — depth write ON.
     * Pass 2: transparent geosets (BLEND, ADDITIVE, etc.) — depth write OFF.
     */
    private static final float[] IDENTITY_4X4 = {
        1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1
    };

    private void drawPerGeoset(float[] mvp, float[] mv, int shader, int mvpLoc, int mvLoc, int samplerLoc,
                                int hasTexLoc, int alphaThreshLoc, int alphaLoc, int uvTransformLoc, int geosetColorLoc, int unshadedLoc, int lightDirLoc) {
        glUseProgram(shader);
        glUniformMatrix4fv(mvpLoc, false, mvp);
        if (mvLoc >= 0) glUniformMatrix4fv(mvLoc, false, mv);
        // Set light direction in view space (transform model-space light by the model-view matrix)
        if (lightDirLoc >= 0) {
            float[] ld = transformDirByMat(mv, LIGHT_DIR[0], LIGHT_DIR[1], LIGHT_DIR[2]);
            glUniform3f(lightDirLoc, ld[0], ld[1], ld[2]);
        }
        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);

        // Pass 1: opaque layers
        for (int gi = 0; gi < geoVao.length; gi++) {
            if (geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
            if (!geosetVisible(gi)) continue;
            float geoAlpha = geosetAlpha(gi);
            if (geoAlpha <= 0f) continue;
            setGeosetColorUniform(geosetColorLoc, gi);
            drawGeosetLayers(gi, samplerLoc, hasTexLoc, alphaThreshLoc, alphaLoc, uvTransformLoc, unshadedLoc, geoAlpha, true);
        }

        // Pass 2: transparent layers
        glEnable(GL_BLEND);
        glDepthMask(false);
        for (int gi = 0; gi < geoVao.length; gi++) {
            if (geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
            if (!geosetVisible(gi)) continue;
            float geoAlpha = geosetAlpha(gi);
            if (geoAlpha <= 0f) continue;
            setGeosetColorUniform(geosetColorLoc, gi);
            drawGeosetLayers(gi, samplerLoc, hasTexLoc, alphaThreshLoc, alphaLoc, uvTransformLoc, unshadedLoc, geoAlpha, false);
        }
        glDepthMask(true);
        glDisable(GL_BLEND);

        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
    }

    private void setUVTransformUniform(int loc, int gi, int li) {
        if (loc < 0) return;
        float[] m = null;
        if (layerUVTransforms != null && gi < layerUVTransforms.length
                && layerUVTransforms[gi] != null && li < layerUVTransforms[gi].length) {
            m = layerUVTransforms[gi][li];
        }
        glUniformMatrix4fv(loc, false, m != null ? m : IDENTITY_4X4);
    }

    private void setGeosetColorUniform(int loc, int gi) {
        if (loc < 0) return;
        float[] c = (geosetColorValues != null && gi < geosetColorValues.length)
                ? geosetColorValues[gi] : null;
        if (c != null) {
            glUniform3f(loc, c[0], c[1], c[2]);
        } else {
            glUniform3f(loc, 1f, 1f, 1f); // white = no tint
        }
    }

    private float geosetAlpha(int gi) {
        return (geosetAlphaValues != null && gi < geosetAlphaValues.length)
                ? geosetAlphaValues[gi] : 1.0f;
    }

    private boolean geosetVisible(int gi) {
        return geosetVisibility == null || gi < 0 || gi >= geosetVisibility.length || geosetVisibility[gi];
    }

    /**
     * Draws all layers of a geoset matching the requested pass (opaque or transparent).
     * @param opaquePass true = draw opaque layers only, false = draw transparent layers only
     */
    private void drawGeosetLayers(int gi, int samplerLoc, int hasTexLoc, int alphaThreshLoc,
                                   int alphaLoc, int uvTransformLoc, int unshadedLoc, float geoAlpha, boolean opaquePass) {
        if (geoTex == null || gi >= geoTex.length || geoTex[gi] == null) return;
        var layers = (gi < texData.length) ? texData[gi].layers() : java.util.List.<GeosetTexData.LayerTexData>of();
        int layerCount = geoTex[gi].length;

        int matId = (geosetMaterialId != null && gi < geosetMaterialId.length) ? geosetMaterialId[gi] : -1;

        // If no multi-layer data, use legacy single-layer path
        if (layers.isEmpty()) {
            if (matId >= 0 && !isLayerVisible(matId, 0)) return;
            boolean isOpaque = (gi < texData.length) ? texData[gi].isOpaque() : true;
            if (opaquePass != isOpaque) return;
            if (!opaquePass) applyBlendMode(texData[gi].filterMode());
            if (unshadedLoc >= 0) glUniform1i(unshadedLoc, 0);
            setUVTransformUniform(uvTransformLoc, gi, 0);
            drawSingleLayer(gi, 0, texData[gi].filterMode(), 1.0f, samplerLoc, hasTexLoc, alphaThreshLoc, alphaLoc, geoAlpha);
            return;
        }

        glBindVertexArray(geoVao[gi]);
        for (int li = 0; li < Math.min(layerCount, layers.size()); li++) {
            if (matId >= 0 && !isLayerVisible(matId, li)) continue;
            var layer = layers.get(li);
            boolean layerOpaque = layer.isOpaque();
            if (opaquePass != layerOpaque) continue;

            if (!opaquePass) {
                if (layer.replaceableId() == 2) {
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE);
                } else {
                    applyBlendMode(layer.filterMode());
                }
            }

            // Two-sided flag per layer
            boolean twoSided = layer.isTwoSided();
            if (twoSided) glDisable(GL_CULL_FACE);

            // Unshaded flag per layer
            if (unshadedLoc >= 0) glUniform1i(unshadedLoc, layer.isUnshaded() ? 1 : 0);

            // UV transform per layer (only the layer with a texture animation gets transformed)
            setUVTransformUniform(uvTransformLoc, gi, li);

            // Swap UV VBO if this layer uses a different coord set
            if (geoLayerUvVbo != null && gi < geoLayerUvVbo.length
                    && geoLayerUvVbo[gi] != null && li < geoLayerUvVbo[gi].length
                    && geoLayerUvVbo[gi][li] != 0) {
                glBindBuffer(GL_ARRAY_BUFFER, geoLayerUvVbo[gi][li]);
                glVertexAttribPointer(1, 2, GL_FLOAT, false, 8, 0L);
                glEnableVertexAttribArray(1);
            }

            float layerAlpha = layer.alpha();
            if (layerAlphaValues != null && gi < layerAlphaValues.length
                    && li < layerAlphaValues[gi].length) {
                layerAlpha = layerAlphaValues[gi][li];
            }
            drawSingleLayer(gi, li, layer.filterMode(), layerAlpha, samplerLoc, hasTexLoc, alphaThreshLoc, alphaLoc, geoAlpha);

            // Restore default UV VBO if we swapped
            if (geoLayerUvVbo != null && gi < geoLayerUvVbo.length
                    && geoLayerUvVbo[gi] != null && li < geoLayerUvVbo[gi].length
                    && geoLayerUvVbo[gi][li] != 0 && geoUvVbo[gi] != 0) {
                glBindBuffer(GL_ARRAY_BUFFER, geoUvVbo[gi]);
                glVertexAttribPointer(1, 2, GL_FLOAT, false, 8, 0L);
            }

            if (twoSided) glEnable(GL_CULL_FACE);
        }
    }

    private void drawSingleLayer(int gi, int li, int filterMode, float layerAlpha,
                                  int samplerLoc, int hasTexLoc, int alphaThreshLoc,
                                  int alphaLoc, float geoAlpha) {
        boolean hasTex = geoTex[gi] != null && li < geoTex[gi].length && geoTex[gi][li] != 0;
        glUniform1i(hasTexLoc, hasTex ? 1 : 0);
        float threshold = (filterMode == 1) ? 0.75f : 0.0f;
        glUniform1f(alphaThreshLoc, threshold);
        glUniform1f(alphaLoc, geoAlpha * layerAlpha);
        if (hasTex) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, geoTex[gi][li]);
            glUniform1i(samplerLoc, 0);
        }
        glBindVertexArray(geoVao[gi]);
        glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
    }

    /** Sets GL blend function for a given MDX filter mode ordinal. */
    static void applyBlendMode(int filterMode) {
        switch (filterMode) {
            case 2 -> // BLEND
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            case 3, 4 -> // ADDITIVE, ADDALPHA
                glBlendFunc(GL_SRC_ALPHA, GL_ONE);
            case 5 -> // MODULATE
                glBlendFunc(GL_ZERO, GL_SRC_COLOR);
            case 6 -> // MODULATE2X
                glBlendFunc(GL_DST_COLOR, GL_SRC_COLOR);
            default ->
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    /**
     * Calls AWTGLCanvas.disposeCanvas() via reflection from the render thread.
     * Must be called while the GL context is current (i.e. from the render thread after the loop).
     */
    private void disposeGlCanvas() {
        try {
            java.lang.reflect.Method m = AWTGLCanvas.class.getDeclaredMethod("disposeCanvas");
            m.setAccessible(true);
            m.invoke(this);
        } catch (Exception ex) {
            System.err.println("[GL] disposeGlCanvas: " + ex);
        }
    }

    /**
     * Stop the render thread and wait for it to exit.
     *
     * MUST be called while the EDT does NOT hold the AWT toolkit lock (i.e. from
     * WindowListener.windowClosing(), before DISPOSE_ON_CLOSE calls dispose()).
     * If called from removeNotify() (AWT lock held), JAWT_DrawingSurface_Lock() in
     * the render thread will deadlock waiting for that same lock.
     */
    public void stopRenderThread() {
        renderRunning = false;
        if (renderThread != null) renderThread.interrupt();
        if (renderStopped != null) {
            try {
                renderStopped.await(2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void removeNotify() {
        // Signal the render thread to stop (if not already stopped by windowClosing).
        renderRunning = false;
        if (renderThread != null) renderThread.interrupt();
        // Brief non-blocking wait — if already stopped via windowClosing this is instant.
        if (renderStopped != null) {
            try { renderStopped.await(100, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        // By now the render thread has exited and called disposeGlCanvas(), which set ds=null
        // inside PlatformWin32GLCanvas. The super.removeNotify() → disposeCanvas() call below
        // will therefore be a safe no-op (the ds null-check inside dispose() prevents
        // JAWT_FreeDrawingSurface from being called with address=0, which is a fatal native
        // crash on JDK 25).
        try {
            super.removeNotify();
        } catch (NullPointerException ex) {
            if (ex.getMessage() == null || !ex.getMessage().contains("JAWTDrawingSurface")) throw ex;
        }
    }

    // ── Animation ────────────────────────────────────────────────────────────

    private void advanceAnimation() {
        long nowNs = System.nanoTime(), prev = lastNanoNs;
        if (prev == 0L) { lastNanoNs = nowNs; lastDtSec = 0f; return; }
        long deltaNs = nowNs - prev; lastNanoNs = nowNs;
        // Real per-frame dt; ribbon/particle simulation uses it but only runs while playing.
        lastDtSec = (float)(deltaNs / 1_000_000_000.0 * animSpeed);
        if (!animPlaying) return;
        long deltaMs = (long)(deltaNs / 1_000_000.0 * animSpeed);
        globalSeqTimeMs += deltaMs;
        SequenceInfo seq = animData.sequences().get(currentSeqIdx);
        long duration = seq.end() - seq.start();
        if (duration <= 0) return;
        long newTime = animTimeMs + deltaMs;
        if (newTime >= seq.end()) {
            if (animLooping) {
                long t = newTime - seq.start();
                animTimeMs = ((t % duration) + duration) % duration + seq.start();
            } else {
                animTimeMs = seq.end();
                animPlaying = false;
                Runnable cb = onAnimationFinished;
                if (cb != null) SwingUtilities.invokeLater(cb);
            }
        } else {
            animTimeMs = newTime;
        }
    }

    private void sampleGeosetAlpha() {
        if (geosetAlphaValues == null) return;
        if (currentSeqIdx < 0 || currentSeqIdx >= animData.sequences().size()) return;
        SequenceInfo seq = animData.sequences().get(currentSeqIdx);
        long[] globalSeqs = animData.globalSequences();
        for (int gi = 0; gi < geosetAlphaValues.length; gi++) {
            AnimTrack track = animData.geosetAlpha().get(gi);
            if (track != null && !track.isEmpty()) {
                if (track.isGlobal() || hasKeysInRange(track, seq.start(), seq.end())) {
                    float val = BoneAnimator.interpTrackScalar(track, animTimeMs, seq.start(), seq.end(), globalSeqs, globalSeqTimeMs, 1f);
                    geosetAlphaValues[gi] = Math.max(0f, Math.min(1f, val));
                } else {
                    geosetAlphaValues[gi] = 1.0f;
                }
            } else {
                geosetAlphaValues[gi] = 1.0f;
            }
        }
    }

    private void sampleLayerAlpha() {
        if (layerAlphaValues == null) return;
        if (currentSeqIdx < 0 || currentSeqIdx >= animData.sequences().size()) return;
        SequenceInfo seq = animData.sequences().get(currentSeqIdx);
        long[] globalSeqs = animData.globalSequences();
        for (int gi = 0; gi < layerAlphaValues.length; gi++) {
            var layers = (gi < texData.length) ? texData[gi].layers() : java.util.List.<GeosetTexData.LayerTexData>of();
            for (int li = 0; li < layerAlphaValues[gi].length; li++) {
                float staticAlpha = (li < layers.size()) ? layers.get(li).alpha() : 1.0f;
                AnimTrack track = animData.layerAlpha().get(ModelAnimData.layerKey(gi, li));
                if (track != null && !track.isEmpty()) {
                    if (track.isGlobal() || hasKeysInRange(track, seq.start(), seq.end())) {
                        float val = BoneAnimator.interpTrackScalar(track, animTimeMs, seq.start(), seq.end(), globalSeqs, globalSeqTimeMs, staticAlpha);
                        layerAlphaValues[gi][li] = Math.max(0f, Math.min(1f, val));
                    } else {
                        layerAlphaValues[gi][li] = staticAlpha;
                    }
                }
            }
        }
    }

    private void sampleGeosetColor() {
        if (geosetColorValues == null) return;
        if (currentSeqIdx < 0 || currentSeqIdx >= animData.sequences().size()) return;
        SequenceInfo seq = animData.sequences().get(currentSeqIdx);
        long[] globalSeqs = animData.globalSequences();
        for (int gi = 0; gi < geosetColorValues.length; gi++) {
            AnimTrack track = animData.geosetColor().get(gi);
            if (track != null && !track.isEmpty()) {
                if (track.isGlobal() || hasKeysInRange(track, seq.start(), seq.end())) {
                    float[] rgb = BoneAnimator.interpTrackVec3(track, animTimeMs, seq.start(), seq.end(), globalSeqs, globalSeqTimeMs, 1f, 1f, 1f);
                    geosetColorValues[gi] = rgb;
                } else {
                    // Fall back to static color or white
                    geosetColorValues[gi] = animData.geosetStaticColor().get(gi);
                }
            }
            // If no animated track, keep whatever was initialized (static color or null=white)
        }
    }

    private void sampleTextureAnims() {
        if (layerUVTransforms == null) return;
        Map<Long, TextureAnimTracks> taMap = animData.textureAnims();
        long[] globalSeqs = animData.globalSequences();
        for (int gi = 0; gi < layerUVTransforms.length; gi++) {
            if (layerUVTransforms[gi] == null) continue;
            for (int li = 0; li < layerUVTransforms[gi].length; li++) {
                TextureAnimTracks ta = taMap.get(ModelAnimData.layerKey(gi, li));
                if (ta == null || !ta.hasAnimation()) {
                    layerUVTransforms[gi][li] = null; // identity
                    continue;
                }

                // Determine time for non-global tracks
                long t;
                long s0, s1;
                if (currentSeqIdx >= 0 && currentSeqIdx < animData.sequences().size()) {
                    SequenceInfo seq = animData.sequences().get(currentSeqIdx);
                    t = animTimeMs;
                    s0 = seq.start();
                    s1 = seq.end();
                } else {
                    // No sequence selected — only global tracks can animate
                    if (ta.globalSequenceId() < 0) {
                        layerUVTransforms[gi][li] = null;
                        continue;
                    }
                    t = 0; s0 = 0; s1 = 0;
                }

                // interpTrack* auto-dispatches to cyclic for global sequence tracks
                float[] trans = BoneAnimator.interpTrackVec3(ta.translation(), t, s0, s1, globalSeqs, globalSeqTimeMs, 0, 0, 0);
                float[] rot = BoneAnimator.interpTrackQuat(ta.rotation(), t, s0, s1, globalSeqs, globalSeqTimeMs);
                float[] scl = BoneAnimator.interpTrackVec3(ta.scale(), t, s0, s1, globalSeqs, globalSeqTimeMs, 1, 1, 1);
                layerUVTransforms[gi][li] = buildUVTransformMatrix(trans, rot, scl);
            }
        }
    }

    /**
     * Builds a 4x4 column-major UV transform matrix from T/R/S around center (0.5, 0.5).
     * TRS order: translate to origin → scale → rotate → translate back → apply translation.
     */
    static float[] buildUVTransformMatrix(float[] trans, float[] rot, float[] scl) {
        float cx = 0.5f, cy = 0.5f;
        // Quaternion → rotation matrix (2D: only z-axis rotation matters for UV, but we do full 3D)
        float qx = rot[0], qy = rot[1], qz = rot[2], qw = rot[3];
        float r00 = 1 - 2*(qy*qy + qz*qz), r01 = 2*(qx*qy - qz*qw), r02 = 2*(qx*qz + qy*qw);
        float r10 = 2*(qx*qy + qz*qw),     r11 = 1 - 2*(qx*qx + qz*qz), r12 = 2*(qy*qz - qx*qw);
        float r20 = 2*(qx*qz - qy*qw),     r21 = 2*(qy*qz + qx*qw),     r22 = 1 - 2*(qx*qx + qy*qy);

        float sx = scl[0], sy = scl[1], sz = scl[2];

        // M = T(trans) * T(center) * R * S * T(-center)
        // Combine: first translate by -center, then scale, then rotate, then translate by center+trans
        float m00 = r00*sx, m01 = r01*sy, m02 = r02*sz;
        float m10 = r10*sx, m11 = r11*sy, m12 = r12*sz;
        float m20 = r20*sx, m21 = r21*sy, m22 = r22*sz;

        float tx = -cx*m00 - cy*m01 + cx + trans[0];
        float ty = -cx*m10 - cy*m11 + cy + trans[1];
        float tz = -cx*m20 - cy*m21 + trans[2];

        // Column-major 4x4
        return new float[] {
            m00, m10, m20, 0,
            m01, m11, m21, 0,
            m02, m12, m22, 0,
            tx,  ty,  tz,  1
        };
    }

    /** Returns true if the track has at least one keyframe within [start, end]. */
    private static boolean hasKeysInRange(AnimTrack track, long start, long end) {
        for (long f : track.frames()) {
            if (f >= start && f <= end) return true;
        }
        return false;
    }

    /**
     * Computes the camera rotation quaternion in model space (Z-up WC3 coordinates).
     * This tells billboard nodes which direction the camera is facing from.
     *
     * The view matrix applies: translate * Rx(pitch) * Ry(yaw) * Rx(-90)
     * The camera rotation in model space (Z-up) is the inverse of the
     * rotational part: Rx(90) * Ry(-yaw) * Rx(-pitch)
     */
    private float[] computeCameraRotationQuat() {
        // Build the inverse camera rotation in Z-up model space
        // Rx(90) * Ry(-yaw) * Rx(-pitch)
        float[] q = quatFromEulerX(90f);
        q = quatMul(q, quatFromEulerY(-yawDegrees));
        q = quatMul(q, quatFromEulerX(-pitchDegrees));
        return q;
    }

    private static float[] quatFromEulerX(float degrees) {
        float r = (float) Math.toRadians(degrees) * 0.5f;
        return new float[]{(float) Math.sin(r), 0, 0, (float) Math.cos(r)};
    }

    private static float[] quatFromEulerY(float degrees) {
        float r = (float) Math.toRadians(degrees) * 0.5f;
        return new float[]{0, (float) Math.sin(r), 0, (float) Math.cos(r)};
    }

    private static float[] quatMul(float[] a, float[] b) {
        return new float[]{
            a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
            a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
            a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
            a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]
        };
    }

    private void uploadAnimatedVertices() {
        SequenceInfo seq = animData.sequences().get(currentSeqIdx);
        // Don't pass camera rotation for mesh vertex transformation —
        // billboard bones just cancel parent rotation (Reteras convention).
        // Particle/ribbon billboarding is handled separately in computeBillboardVectors().
        Map<Integer, float[]> worldMap = BoneAnimator.computeWorldMatrices(
                animData.bones(), animTimeMs, seq.start(), seq.end(), animData.globalSequences(), globalSeqTimeMs);
        lastWorldMap = worldMap; // cache for ribbon simulation and node names overlay

        float[] bindNormals = mesh.normals();

        // Update flat combined buffer (vertices + normals)
        int meshVertOffset = 0;
        for (GeosetSkinData skin : animData.geosets()) {
            int vc = skin.vertexCount(); if (vc == 0) continue;
            if (skin.hasSkinning()) {
                for (int vi = 0; vi < vc; vi++) {
                    float[] p = transformVertex(skin, vi, worldMap);
                    int base = (meshVertOffset + vi) * 3;
                    animatedVertices[base] = p[0]; animatedVertices[base+1] = p[1]; animatedVertices[base+2] = p[2];
                    // Transform normal using upper-left 3x3 of averaged bone matrix
                    float[] n = transformNormal(skin, vi, meshVertOffset, bindNormals, worldMap);
                    animatedNormals[base] = n[0]; animatedNormals[base+1] = n[1]; animatedNormals[base+2] = n[2];
                }
            } else {
                System.arraycopy(skin.bindVertices(), 0, animatedVertices, meshVertOffset * 3, vc * 3);
                System.arraycopy(bindNormals, meshVertOffset * 3, animatedNormals, meshVertOffset * 3, vc * 3);
            }
            meshVertOffset += vc;
        }
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, animatedVertices);
        glBindBuffer(GL_ARRAY_BUFFER, meshNormVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, animatedNormals);

        // Update per-geoset position and normal VBOs
        if (geoVbo != null && geoVertCount != null) {
            int off = 0;
            for (int gi = 0; gi < geoVbo.length; gi++) {
                int vc = geoVertCount[gi];
                if (geoVbo[gi] != 0 && vc > 0) {
                    float[] slice = new float[vc * 3];
                    System.arraycopy(animatedVertices, off * 3, slice, 0, vc * 3);
                    glBindBuffer(GL_ARRAY_BUFFER, geoVbo[gi]);
                    glBufferSubData(GL_ARRAY_BUFFER, 0L, slice);
                    // Upload animated normals for this geoset
                    if (geoNormVbo != null && geoNormVbo[gi] != 0) {
                        float[] nSlice = new float[vc * 3];
                        System.arraycopy(animatedNormals, off * 3, nSlice, 0, vc * 3);
                        glBindBuffer(GL_ARRAY_BUFFER, geoNormVbo[gi]);
                        glBufferSubData(GL_ARRAY_BUFFER, 0L, nSlice);
                    }
                }
                off += vc;
            }
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Check if any bone has a billboard flag set. */
    private boolean hasBillboardBones() {
        for (BoneNode b : animData.bones()) {
            if (b.isBillboarded()) return true;
        }
        return false;
    }

    /**
     * Transform vertices using billboard bone matrices without a selected sequence.
     * Uses time=0 and dummy sequence range, but still passes camera rotation
     * so billboard bones face the camera.
     */
    private void uploadAnimatedVerticesNoSequence() {
        Map<Integer, float[]> worldMap = BoneAnimator.computeWorldMatrices(
                animData.bones(), 0, 0, 0, animData.globalSequences(), globalSeqTimeMs);
        lastWorldMap = worldMap;

        float[] bindNormals = mesh.normals();
        int meshVertOffset = 0;
        for (GeosetSkinData skin : animData.geosets()) {
            int vc = skin.vertexCount(); if (vc == 0) continue;
            if (skin.hasSkinning()) {
                for (int vi = 0; vi < vc; vi++) {
                    float[] p = transformVertex(skin, vi, worldMap);
                    int base = (meshVertOffset + vi) * 3;
                    animatedVertices[base] = p[0]; animatedVertices[base+1] = p[1]; animatedVertices[base+2] = p[2];
                    float[] n = transformNormal(skin, vi, meshVertOffset, bindNormals, worldMap);
                    animatedNormals[base] = n[0]; animatedNormals[base+1] = n[1]; animatedNormals[base+2] = n[2];
                }
            } else {
                System.arraycopy(skin.bindVertices(), 0, animatedVertices, meshVertOffset * 3, vc * 3);
                System.arraycopy(bindNormals, meshVertOffset * 3, animatedNormals, meshVertOffset * 3, vc * 3);
            }
            meshVertOffset += vc;
        }
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, animatedVertices);
        glBindBuffer(GL_ARRAY_BUFFER, meshNormVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, animatedNormals);

        if (geoVbo != null && geoVertCount != null) {
            int off = 0;
            for (int gi = 0; gi < geoVbo.length; gi++) {
                int vc = geoVertCount[gi];
                if (geoVbo[gi] != 0 && vc > 0) {
                    float[] slice = new float[vc * 3];
                    System.arraycopy(animatedVertices, off * 3, slice, 0, vc * 3);
                    glBindBuffer(GL_ARRAY_BUFFER, geoVbo[gi]);
                    glBufferSubData(GL_ARRAY_BUFFER, 0L, slice);
                    float[] nSlice = new float[vc * 3];
                    System.arraycopy(animatedNormals, off * 3, nSlice, 0, vc * 3);
                    glBindBuffer(GL_ARRAY_BUFFER, geoNormVbo[gi]);
                    glBufferSubData(GL_ARRAY_BUFFER, 0L, nSlice);
                }
                off += vc;
            }
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    static float[] transformVertex(GeosetSkinData skin, int vi, Map<Integer, float[]> wm) {
        float bx = skin.bindVertices()[vi*3], by = skin.bindVertices()[vi*3+1], bz = skin.bindVertices()[vi*3+2];
        int[] vg = skin.vertexGroup();
        int gi = (vg != null && vi < vg.length) ? vg[vi] : 0;
        int[][] g = skin.groupBoneObjectIds();
        if (gi >= g.length || g[gi].length == 0) return new float[]{bx,by,bz};
        float[] avg = new float[16]; int cnt = 0;
        for (int bid : g[gi]) { float[] m = wm.get(bid); if (m!=null){for(int j=0;j<16;j++)avg[j]+=m[j];cnt++;} }
        if (cnt == 0) return new float[]{bx,by,bz};
        float inv = 1f/cnt; for(int j=0;j<16;j++) avg[j]*=inv;
        return new float[]{
                avg[0]*bx+avg[4]*by+avg[8]*bz+avg[12],
                avg[1]*bx+avg[5]*by+avg[9]*bz+avg[13],
                avg[2]*bx+avg[6]*by+avg[10]*bz+avg[14]
        };
    }

    /** Transform a vertex normal using the upper-left 3x3 of the averaged bone matrix, then normalize. */
    static float[] transformNormal(GeosetSkinData skin, int vi, int meshVertOffset,
                                   float[] bindNormals, Map<Integer, float[]> wm) {
        int nBase = (meshVertOffset + vi) * 3;
        float nx = bindNormals[nBase], ny = bindNormals[nBase+1], nz = bindNormals[nBase+2];
        int[] vg = skin.vertexGroup();
        int gi = (vg != null && vi < vg.length) ? vg[vi] : 0;
        int[][] g = skin.groupBoneObjectIds();
        if (gi >= g.length || g[gi].length == 0) return new float[]{nx,ny,nz};
        float[] avg = new float[16]; int cnt = 0;
        for (int bid : g[gi]) { float[] m = wm.get(bid); if (m!=null){for(int j=0;j<16;j++)avg[j]+=m[j];cnt++;} }
        if (cnt == 0) return new float[]{nx,ny,nz};
        float inv = 1f/cnt; for(int j=0;j<16;j++) avg[j]*=inv;
        // Apply only 3x3 rotation part (no translation)
        float rx = avg[0]*nx + avg[4]*ny + avg[8]*nz;
        float ry = avg[1]*nx + avg[5]*ny + avg[9]*nz;
        float rz = avg[2]*nx + avg[6]*ny + avg[10]*nz;
        // Normalize
        float len = (float)Math.sqrt(rx*rx + ry*ry + rz*rz);
        if (len > 0.0001f) { rx /= len; ry /= len; rz /= len; }
        return new float[]{rx, ry, rz};
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Freezes the model in its default (rest) pose with no animation playing. */
    public void setNoSequence() {
        currentSeqIdx = -1;
        animPlaying   = false;
        animTimeMs    = 0L;
        pendingNoSeqPose = true;
        resetRibbons();
        resetParticles2();
    }
    public void setSequence(int idx) {
        if (idx < 0 || idx >= animData.sequences().size()) return;
        currentSeqIdx = idx;
        SequenceInfo seq = animData.sequences().get(idx);
        animTimeMs    = seq.start();
        lastNanoNs    = 0L;
        resetRibbons();
        resetParticles2();
        var cb = onSequenceChanged;
        if (cb != null) SwingUtilities.invokeLater(() -> cb.accept(idx));
        // Update extent overlay to show sequence extents if available
        pendingExtentUpdate = seq;
    }
    private volatile SequenceInfo pendingExtentUpdate;
    public void setPlaying(boolean p) { animPlaying = p; if (p) lastNanoNs = 0L; }
    public boolean isPlaying()        { return animPlaying; }
    public void setSpeed(float s)     { animSpeed = Math.max(0.01f, s); }
    public boolean hasAnimationData() { return animData.hasAnimation(); }
    public long getAnimTimeMs()       { return animTimeMs; }
    public void setAnimTimeMs(long t) {
        if (currentSeqIdx < 0 || currentSeqIdx >= animData.sequences().size()) return;
        SequenceInfo seq = animData.sequences().get(currentSeqIdx);
        animTimeMs = Math.max(seq.start(), Math.min(seq.end(), t));
        lastNanoNs = 0L;
    }
    public SequenceInfo getCurrentSequence() {
        if (currentSeqIdx < 0 || currentSeqIdx >= animData.sequences().size()) return null;
        return animData.sequences().get(currentSeqIdx);
    }
    /** Request a screenshot. The returned future completes on the next render frame. */
    public java.util.concurrent.CompletableFuture<BufferedImage> requestScreenshot() {
        var future = new java.util.concurrent.CompletableFuture<BufferedImage>();
        screenshotRequest = future;
        return future;
    }
    public void setLooping(boolean l)          { animLooping = l; }
    private volatile Runnable onAnimationFinished;
    public void setOnAnimationFinished(Runnable r) { onAnimationFinished = r; }
    private volatile java.util.function.IntConsumer onSequenceChanged;
    public void setOnSequenceChanged(java.util.function.IntConsumer c) { onSequenceChanged = c; }
    public void setTeamColor(int idx)          { int v = TeamColorOptions.clampIndex(idx); if (v != teamColorIdx) { teamColorIdx = v; tcDirty = true; } }
    public int  getTeamColor()                 { return teamColorIdx; }
    public void setShadingMode(ShadingMode m) { shadingMode = m != null ? m : ShadingMode.SOLID; }
    public void setWireframe(boolean w)       { wireframe = w; }
    public void setShowExtent(boolean e)     { showExtent = e; }
    public void setShowBones(boolean b)       { showBones = b; }
    public void setShowHelpers(boolean h)    { showHelpers = h; }
    public void setShowAttachments(boolean a){ showAttachments = a; }
    public void setShowRibbonEmitters(boolean r) { showRibbonEmitters = r; }
    public void setShowParticleEmitters(boolean p) { showParticleEmitters = p; }
    public void setShowNodeNames(boolean n)  { showNodeNames = n; }
    public void setShowGrid(boolean g)       { showGrid = g; }
    public void setShowCollision(boolean c)  { showCollision = c; }
    public void setNodeSize(float s)         { nodeSize = Math.max(0.5f, Math.min(20f, s)); }
    public void setHighlightedBoneId(int id)    { highlightedBoneId = id; }
    public void setHighlightedGeosetIdx(int gi) { highlightedGeosetIdx = gi; }
    public void setHighlightWireframe(boolean wireframe) { highlightWireframe = wireframe; }
    public void setHighlightedGeosetIndices(int[] indices) { highlightedGeosetIndices = indices; }
    public void setGeosetVisible(int gi, boolean visible) {
        if (geosetVisibility == null && texData != null) {
            geosetVisibility = new boolean[texData.length];
            java.util.Arrays.fill(geosetVisibility, true);
        }
        if (geosetVisibility != null && gi >= 0 && gi < geosetVisibility.length) {
            geosetVisibility[gi] = visible;
        }
    }
    public boolean isGeosetVisible(int gi) { return geosetVisible(gi); }
    public int getGeosetCount() { return texData != null ? texData.length : 0; }

    // Emitter visibility (by objectId)
    private volatile java.util.Set<Integer> disabledEmitters; // null = all enabled
    public void setEmitterEnabled(int objectId, boolean enabled) {
        if (disabledEmitters == null) disabledEmitters = java.util.concurrent.ConcurrentHashMap.newKeySet();
        if (enabled) disabledEmitters.remove(objectId);
        else         disabledEmitters.add(objectId);
    }
    public boolean isEmitterEnabled(int objectId) {
        return disabledEmitters == null || !disabledEmitters.contains(objectId);
    }

    // Layer visibility (by materialIndex + layerIndex)
    private volatile java.util.Set<Long> disabledLayers; // null = all visible
    public void setLayerVisible(int materialIdx, int layerIdx, boolean visible) {
        if (disabledLayers == null) disabledLayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        long key = ((long) materialIdx << 16) | layerIdx;
        if (visible) disabledLayers.remove(key);
        else         disabledLayers.add(key);
    }
    public boolean isLayerVisible(int materialIdx, int layerIdx) {
        return disabledLayers == null || !disabledLayers.contains(((long) materialIdx << 16) | layerIdx);
    }

    /** Snaps the camera to the model's camera node position/target. */
    public void applyCameraView(CameraNode cam) {
        if (cam == null) return;
        // Save current orbit state so we can restore it later
        savedYaw = yawDegrees; savedPitch = pitchDegrees;
        savedDistance = distance; savedPanX = panX; savedPanY = panY;
        savedFrameCenterX = frameCenterX; savedFrameCenterY = frameCenterY; savedFrameCenterZ = frameCenterZ;

        // Set orbit center to the camera's target position (Z-up model space)
        frameCenterX = cam.targetPosition()[0];
        frameCenterY = cam.targetPosition()[1];
        frameCenterZ = cam.targetPosition()[2];

        // Direction from eye to target in Z-up model space
        float dx = cam.position()[0] - cam.targetPosition()[0];
        float dy = cam.position()[1] - cam.targetPosition()[1];
        float dz = cam.position()[2] - cam.targetPosition()[2];
        float dist3d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist3d < 0.001f) dist3d = 100f;

        // Derive orbit angles from the eye-to-target direction in Z-up space
        yawDegrees = (float) Math.toDegrees(Math.atan2(-dx, -dy));
        pitchDegrees = (float) Math.toDegrees(Math.asin(clamp(dz / dist3d, -1f, 1f)));
        distance = dist3d * modelScale;
        panX = 0f;
        panY = 0f;

        usingCameraView = true;
        cameraViewNode = cam;
    }

    /** Restores the orbit camera after Camera View. */
    public void resetCameraView() {
        if (!usingCameraView) return;
        yawDegrees = savedYaw; pitchDegrees = savedPitch;
        distance = savedDistance; panX = savedPanX; panY = savedPanY;
        frameCenterX = savedFrameCenterX; frameCenterY = savedFrameCenterY; frameCenterZ = savedFrameCenterZ;
        usingCameraView = false;
        cameraViewNode = null;
    }
    public void setBackgroundColor(String hex) {
        if (hex == null || hex.length() < 6) return;
        try {
            bgR = Integer.parseInt(hex.substring(0, 2), 16) / 255f;
            bgG = Integer.parseInt(hex.substring(2, 4), 16) / 255f;
            bgB = Integer.parseInt(hex.substring(4, 6), 16) / 255f;
        } catch (NumberFormatException ignored) {}
    }
    public ModelMesh getModelMesh()           { return mesh; }
    public ModelAnimData getAnimData()        { return animData; }
    public GeosetTexData[] getTexData()       { return texData; }

    /**
     * Renders node names as a GL textured quad overlay.
     * Projects 3D bone positions to 2D, draws text into a BufferedImage,
     * uploads as a GL texture, and draws a fullscreen blended quad.
     */
    private void drawNodeNamesGL(float[] modelMvp, int vpW, int vpH) {
        Map<Integer, float[]> wm = lastWorldMap;
        if (wm == null || vpW <= 0 || vpH <= 0) return;
        BoneNode[] bones = animData.bones();

        // Build list of (name, screenX, screenY)
        java.util.List<int[]> screenPositions = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        boolean anyTypeFilter = showBones || showHelpers || showAttachments || showRibbonEmitters || showParticleEmitters;
        for (BoneNode bone : bones) {
            if (bone.name().isEmpty()) continue;
            if (anyTypeFilter) {
                boolean visible = switch (bone.nodeType()) {
                    case BONE              -> showBones;
                    case HELPER            -> showHelpers;
                    case ATTACHMENT        -> showAttachments;
                    case RIBBON_EMITTER    -> showRibbonEmitters;
                    case PARTICLE_EMITTER2 -> showParticleEmitters;
                };
                if (!visible) continue;
            }

            float[] pos = boneWorldPos(bone, wm);
            float wx = pos[0], wy = pos[1], wz = pos[2];

            float cx = modelMvp[0]*wx + modelMvp[4]*wy + modelMvp[8]*wz + modelMvp[12];
            float cy = modelMvp[1]*wx + modelMvp[5]*wy + modelMvp[9]*wz + modelMvp[13];
            float cw = modelMvp[3]*wx + modelMvp[7]*wy + modelMvp[11]*wz + modelMvp[15];
            if (cw <= 0.001f) continue;

            float ndcX = cx / cw, ndcY = cy / cw;
            int sx = (int) ((ndcX * 0.5f + 0.5f) * vpW);
            int sy = (int) ((1f - (ndcY * 0.5f + 0.5f)) * vpH);

            names.add(bone.name());
            screenPositions.add(new int[]{sx, sy});
        }
        if (names.isEmpty()) return;

        // Render text to BufferedImage
        java.awt.image.BufferedImage textImg = new java.awt.image.BufferedImage(
                vpW, vpH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = textImg.createGraphics();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));
        java.awt.FontMetrics fm = g2.getFontMetrics();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int[] sp = screenPositions.get(i);
            int tw = fm.stringWidth(name);
            int tx = sp[0] - tw / 2;
            int ty = sp[1] + 14;
            // Shadow
            g2.setColor(new java.awt.Color(0, 0, 0, 180));
            g2.drawString(name, tx + 1, ty + 1);
            // Text
            g2.setColor(new java.awt.Color(220, 230, 240));
            g2.drawString(name, tx, ty);
        }
        g2.dispose();

        // Upload as GL texture
        if (nodeNamesTex == 0) nodeNamesTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, nodeNamesTex);
        // Convert to RGBA byte buffer
        int[] pixels = textImg.getRGB(0, 0, vpW, vpH, null, 0, vpW);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(vpW * vpH * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        for (int pixel : pixels) {
            buf.put((byte) ((pixel >> 16) & 0xFF)); // R
            buf.put((byte) ((pixel >> 8) & 0xFF));  // G
            buf.put((byte) (pixel & 0xFF));          // B
            buf.put((byte) ((pixel >> 24) & 0xFF)); // A
        }
        buf.flip();
        if (vpW != nodeNamesTexW || vpH != nodeNamesTexH) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, vpW, vpH, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            nodeNamesTexW = vpW;
            nodeNamesTexH = vpH;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, vpW, vpH, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        }

        // Build fullscreen quad VAO if needed
        if (nodeNamesQuadVao == 0) {
            float[] quad = {
                // pos (x,y), uv (u,v)  — V flipped so BufferedImage top maps to GL top
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                 1f,  1f, 1f, 1f,
                -1f, -1f, 0f, 0f,
                 1f,  1f, 1f, 1f,
                -1f,  1f, 0f, 1f,
            };
            nodeNamesQuadVao = glGenVertexArrays();
            nodeNamesQuadVbo = glGenBuffers();
            glBindVertexArray(nodeNamesQuadVao);
            glBindBuffer(GL_ARRAY_BUFFER, nodeNamesQuadVbo);
            glBufferData(GL_ARRAY_BUFFER, quad, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0L);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8L);
            glEnableVertexAttribArray(1);
            glBindVertexArray(0);
        }

        // Draw fullscreen quad with blending
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_DEPTH_TEST);
        glUseProgram(texShader);
        // Identity MVP (NDC passthrough)
        float[] identity = identity();
        glUniformMatrix4fv(texMvp, false, identity);
        if (texMvLoc >= 0) glUniformMatrix4fv(texMvLoc, false, identity);
        glUniform1i(texHasTex, 1);
        glUniform1f(texAlphaThresh, 0f);
        glUniform1f(texAlphaU, 1f);
        glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, nodeNamesTex);
        glUniform1i(texSampler, 0);
        glBindVertexArray(nodeNamesQuadVao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glUseProgram(0);
    }

    // ── Shader compilation ───────────────────────────────────────────────────

    private void compileSolidShader() {
        solidShader = linkProgram(SOLID_VERT, SOLID_FRAG);
        if (solidShader != 0) { solidMvp = glGetUniformLocation(solidShader,"mvp"); solidColor = glGetUniformLocation(solidShader,"uColor"); solidAlpha = glGetUniformLocation(solidShader,"uAlpha"); }
    }
    private void compileTexShader() {
        texShader = linkProgram(TEX_VERT, TEX_FRAG);
        if (texShader != 0) { texMvp = glGetUniformLocation(texShader,"mvp"); texMvLoc = glGetUniformLocation(texShader,"uModelView"); texSampler = glGetUniformLocation(texShader,"uTex"); texHasTex = glGetUniformLocation(texShader,"uHasTex"); texAlphaThresh = glGetUniformLocation(texShader,"uAlphaThreshold"); texAlphaU = glGetUniformLocation(texShader,"uAlpha"); texUVTransform = glGetUniformLocation(texShader,"uUVTransform"); texGeosetColor = glGetUniformLocation(texShader,"uGeosetColor"); texUnshaded = glGetUniformLocation(texShader,"uUnshaded"); }
    }
    private void compileLitShader() {
        litShader = linkProgram(LIT_VERT, LIT_FRAG);
        if (litShader != 0) { litMvp = glGetUniformLocation(litShader,"mvp"); litMvLoc = glGetUniformLocation(litShader,"uModelView"); litSampler = glGetUniformLocation(litShader,"uTex"); litHasTex = glGetUniformLocation(litShader,"uHasTex"); litAlphaThresh = glGetUniformLocation(litShader,"uAlphaThreshold"); litAlphaU = glGetUniformLocation(litShader,"uAlpha"); litUVTransform = glGetUniformLocation(litShader,"uUVTransform"); litGeosetColor = glGetUniformLocation(litShader,"uGeosetColor"); litUnshaded = glGetUniformLocation(litShader,"uUnshaded"); litLightDir = glGetUniformLocation(litShader,"uLightDir"); }
    }
    private void compileNormalsShader() {
        normalsShader = linkProgram(NORMALS_VERT, NORMALS_FRAG);
        if (normalsShader != 0) { normalsMvp = glGetUniformLocation(normalsShader, "mvp"); normalsMvLoc = glGetUniformLocation(normalsShader, "uModelView"); }
    }
    private void compileVtxColorShader() {
        vtxColorShader = linkProgram(VTX_COLOR_VERT, VTX_COLOR_FRAG);
        if (vtxColorShader != 0) { vtxColorMvp = glGetUniformLocation(vtxColorShader, "mvp"); vtxColorMvLoc = glGetUniformLocation(vtxColorShader, "uModelView"); }
    }
    static int linkProgram(String vs, String fs) {
        int v = compileShader(GL_VERTEX_SHADER,vs), f = compileShader(GL_FRAGMENT_SHADER,fs);
        if (v==0||f==0) return 0;
        int p = glCreateProgram(); glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p); glDeleteShader(v); glDeleteShader(f);
        if (glGetProgrami(p,GL_LINK_STATUS)==GL_FALSE){System.err.println("[GL] link: "+glGetProgramInfoLog(p));glDeleteProgram(p);return 0;}
        return p;
    }
    static int compileShader(int type, String src) {
        int id = glCreateShader(type); glShaderSource(id,src); glCompileShader(id);
        if (glGetShaderi(id,GL_COMPILE_STATUS)==GL_FALSE){System.err.println("[GL] compile: "+glGetShaderInfoLog(id));glDeleteShader(id);return 0;}
        return id;
    }

    // ── Geometry upload ──────────────────────────────────────────────────────

    private void buildGridVao() {
        // Grid in Z-up XY plane at Z=0 (model space), so it aligns with the model's ground
        int half=200,step=20,cnt=0; for(int i=-half;i<=half;i+=step)cnt++;
        gridVertexCount=cnt*4; float[] d=new float[gridVertexCount*3]; int ix=0;
        for(int i=-half;i<=half;i+=step){
            // line along Y: (i, -half, 0) → (i, half, 0)
            d[ix++]=i;d[ix++]=-half;d[ix++]=0;d[ix++]=i;d[ix++]=half;d[ix++]=0;
            // line along X: (-half, i, 0) → (half, i, 0)
            d[ix++]=-half;d[ix++]=i;d[ix++]=0;d[ix++]=half;d[ix++]=i;d[ix++]=0;
        }
        gridVao=glGenVertexArrays();gridVbo=glGenBuffers();glBindVertexArray(gridVao);glBindBuffer(GL_ARRAY_BUFFER,gridVbo);glBufferData(GL_ARRAY_BUFFER,d,GL_STATIC_DRAW);glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);glBindVertexArray(0);
    }

    private void buildMeshVao() {
        float[] v=mesh.vertices(); float[] n=mesh.normals(); int[] ix=mesh.indices();
        if(v.length==0||ix.length==0) return;
        int usage = animData.hasAnimation() ? GL_DYNAMIC_DRAW : GL_STATIC_DRAW;
        meshIndexCount=ix.length; meshVao=glGenVertexArrays();meshVbo=glGenBuffers();meshNormVbo=glGenBuffers();meshEbo=glGenBuffers();
        glBindVertexArray(meshVao);
        glBindBuffer(GL_ARRAY_BUFFER,meshVbo);glBufferData(GL_ARRAY_BUFFER,v,usage);
        glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);
        // Normals at location=2
        glBindBuffer(GL_ARRAY_BUFFER,meshNormVbo);glBufferData(GL_ARRAY_BUFFER,n,usage);
        glVertexAttribPointer(2,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(2);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,meshEbo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,ix,GL_STATIC_DRAW);
        glBindVertexArray(0);
    }

    /**
     * Builds per-geoset VAOs for Textured mode.
     *
     * texData[] has one entry per MESH-INCLUDED geoset.
     * animData.geosets() has one entry per ALL geosets (EMPTY for skipped ones).
     * We iterate animData.geosets(), skip EMPTY entries, and use a separate texIdx counter
     * to index into texData[].
     */
    private void buildPerGeosetVaos() {
        int geoCount = texData.length;
        if (geoCount == 0) return;

        geoVao       = new int[geoCount];
        geoVbo       = new int[geoCount];
        geoNormVbo   = new int[geoCount];
        geoUvVbo     = new int[geoCount];
        geoEbo       = new int[geoCount];
        geoIndexCount= new int[geoCount];
        geoTex       = new int[geoCount][];
        geoLayerUvVbo = new int[geoCount][];
        geoVertCount = new int[geoCount];
        geoIndices   = new int[geoCount][];
        geosetMaterialId = new int[geoCount];
        geoBoneCountVbo = new int[geoCount];

        int[] allIndices = mesh.indices();
        int vertOffset   = 0;
        int indexOffset  = 0;
        int gi           = 0;   // index into texData[] / geoVao[]

        for (GeosetSkinData skin : animData.geosets()) {
            int vc = skin.vertexCount();
            if (vc == 0) continue;   // EMPTY – skipped in mesh
            if (gi >= geoCount) break;
            geosetMaterialId[gi] = skin.materialId();

            // Count indices belonging to this geoset
            // (all adjacent indices in range [vertOffset, vertOffset+vc))
            int faceCount = 0;
            for (int ii = indexOffset; ii < allIndices.length; ii++) {
                if (allIndices[ii] >= vertOffset && allIndices[ii] < vertOffset + vc) faceCount++;
                else break;
            }

            if (faceCount > 0) {
                float[] verts = new float[vc * 3];
                System.arraycopy(mesh.vertices(), vertOffset * 3, verts, 0, vc * 3);

                float[] norms = new float[vc * 3];
                System.arraycopy(mesh.normals(), vertOffset * 3, norms, 0, vc * 3);

                int[] indices = new int[faceCount];
                for (int ii = 0; ii < faceCount; ii++) indices[ii] = allIndices[indexOffset + ii] - vertOffset;
                geoIndices[gi] = indices;

                int usage = animData.hasAnimation() ? GL_DYNAMIC_DRAW : GL_STATIC_DRAW;
                geoVao[gi]        = glGenVertexArrays();
                geoVbo[gi]        = glGenBuffers();
                geoNormVbo[gi]    = glGenBuffers();
                geoEbo[gi]        = glGenBuffers();
                geoIndexCount[gi] = faceCount;
                geoVertCount[gi]  = vc;

                glBindVertexArray(geoVao[gi]);
                glBindBuffer(GL_ARRAY_BUFFER, geoVbo[gi]);
                glBufferData(GL_ARRAY_BUFFER, verts, usage);
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
                glEnableVertexAttribArray(0);

                // Per-vertex normals at location=2
                glBindBuffer(GL_ARRAY_BUFFER, geoNormVbo[gi]);
                glBufferData(GL_ARRAY_BUFFER, norms, usage);
                glVertexAttribPointer(2, 3, GL_FLOAT, false, 12, 0L);
                glEnableVertexAttribArray(2);

                if (texData[gi].hasUvs()) {
                    geoUvVbo[gi] = glGenBuffers();
                    glBindBuffer(GL_ARRAY_BUFFER, geoUvVbo[gi]);
                    glBufferData(GL_ARRAY_BUFFER, texData[gi].uvs(), GL_STATIC_DRAW);
                    glVertexAttribPointer(1, 2, GL_FLOAT, false, 8, 0L);
                    glEnableVertexAttribArray(1);
                }

                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, geoEbo[gi]);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

                // Per-vertex bone color VBOs (location=3)
                if (skin.hasSkinning()) {
                    int[] vg = skin.vertexGroup();
                    int[][] groups = skin.groupBoneObjectIds();

                    // BONE_COUNT: heat map by number of influencing bones
                    float[] countColors = new float[vc * 3];
                    for (int vi = 0; vi < vc; vi++) {
                        int grp = (vg != null && vi < vg.length) ? vg[vi] : 0;
                        int cnt = (grp < groups.length) ? groups[grp].length : 0;
                        float[] c = boneCountColor(cnt);
                        countColors[vi * 3] = c[0]; countColors[vi * 3 + 1] = c[1]; countColors[vi * 3 + 2] = c[2];
                    }
                    geoBoneCountVbo[gi] = glGenBuffers();
                    glBindBuffer(GL_ARRAY_BUFFER, geoBoneCountVbo[gi]);
                    glBufferData(GL_ARRAY_BUFFER, countColors, GL_STATIC_DRAW);
                }

                glBindVertexArray(0);

                // Load textures for all material layers
                var layers = texData[gi].layers();
                if (!layers.isEmpty()) {
                    geoTex[gi] = new int[layers.size()];
                    geoLayerUvVbo[gi] = new int[layers.size()];
                    for (int li = 0; li < layers.size(); li++) {
                        var layer = layers.get(li);
                        geoTex[gi][li] = loadLayerTexture(layer.texturePath(), layer.replaceableId(), teamColorIdx);
                        // If layer uses a different UV set, create a separate UV VBO
                        int coordId = layer.coordId();
                        if (coordId > 0 && texData[gi].uvSets().length > coordId) {
                            float[] layerUvs = texData[gi].uvSets()[coordId];
                            if (layerUvs != null && layerUvs.length > 0) {
                                geoLayerUvVbo[gi][li] = glGenBuffers();
                                glBindBuffer(GL_ARRAY_BUFFER, geoLayerUvVbo[gi][li]);
                                glBufferData(GL_ARRAY_BUFFER, layerUvs, GL_STATIC_DRAW);
                            }
                        }
                    }
                } else {
                    // Fallback for legacy single-layer path
                    geoTex[gi] = new int[1];
                    geoLayerUvVbo[gi] = new int[1];
                    geoTex[gi][0] = loadLayerTexture(texData[gi].texturePath(), texData[gi].replaceableId(), teamColorIdx);
                }

                indexOffset += faceCount;
            }
            vertOffset += vc;
            gi++;
        }
    }

    // Fallback only when team color textures cannot be resolved.
    private static final int[][] DEFAULT_TEAM_COLORS = {
        {255,   3,   3}, // 0 Red
        {  0,  66, 255}, // 1 Blue
        {  0, 206, 209}, // 2 Teal
        { 84,   0, 129}, // 3 Purple
        {255, 252,   0}, // 4 Yellow
        {254, 138,  14}, // 5 Orange
        { 32, 192,   0}, // 6 Green
        {229,  91, 176}, // 7 Pink
        {149, 150, 151}, // 8 Gray
        {126, 191, 241}, // 9 Light Blue
        {  0,  97,  31}, // 10 Dark Green
        { 78,  42,   4}, // 11 Brown
    };

    /** Loads a GL texture for a material layer (handles team color/glow/normal). */
    private int loadLayerTexture(String texPath, int replId, int tc) {
        if (replId == 1 && !texPath.isEmpty()) {
            return loadTeamColorTexture(texPath, tc);
        } else if (replId == 1) {
            return loadTeamColorSwatchTexture(tc);
        } else if (replId == 2) {
            String glowPath = replaceableTexturePath(2, tc);
            int tex = loadGlTexture(glowPath);
            if (tex == 0 && !texPath.isEmpty()) tex = loadGlTexture(texPath);
            return tex;
        } else if (!texPath.isEmpty()) {
            return loadGlTexture(texPath);
        }
        return 0;
    }

    private static String replaceableTexturePath(int replaceableId, int teamColorIdx) {
        String idx = String.format("%02d", teamColorIdx);
        if (replaceableId == 1) return "ReplaceableTextures\\TeamColor\\TeamColor" + idx + ".blp";
        if (replaceableId == 2) return "ReplaceableTextures\\TeamGlow\\TeamGlow" + idx + ".blp";
        return "";
    }

    /**
     * Loads the base texture and composites team color underneath using:
     * result_rgb = base_alpha * base_rgb + (1 - base_alpha) * tc_rgb
     */
    private int loadTeamColorTexture(String basePath, int tcIdx) {
        BufferedImage base = GameDataSource.getInstance().loadTexture(basePath, modelDir, rootDir);
        if (base == null) {
            System.out.println("[GL] TC base texture not found: " + basePath + ", using solid TC");
            return loadTeamColorSwatchTexture(tcIdx);
        }
        int[] tc = resolveTeamColorRgb(tcIdx);
        int w = base.getWidth(), h = base.getHeight();
        boolean rawRaster = base.getColorModel() instanceof java.awt.image.ComponentColorModel;
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r, g, b;
                float a;
                if (rawRaster) {
                    int[] pixel = base.getRaster().getPixel(x, y, (int[]) null);
                    r = pixel[0]; g = pixel[1]; b = pixel[2];
                    a = (pixel.length > 3 ? pixel[3] : 255) / 255f;
                } else {
                    int argb = base.getRGB(x, y);
                    a = ((argb >> 24) & 0xFF) / 255f;
                    r = (argb >> 16) & 0xFF;
                    g = (argb >> 8) & 0xFF;
                    b = argb & 0xFF;
                }
                // Composite: TC shows through transparent areas, base shows in opaque areas
                int cr = Math.round(a * r + (1f - a) * tc[0]);
                int cg = Math.round(a * g + (1f - a) * tc[1]);
                int cb = Math.round(a * b + (1f - a) * tc[2]);
                // Keep full alpha — the geoset's filterMode controls blending in the renderer
                result.setRGB(x, y, 0xFF000000 | (cr << 16) | (cg << 8) | cb);
            }
        }
        System.out.println("[GL] Composited TC " + tcIdx + " with " + basePath + " (" + w + "x" + h + ")");
        return uploadTexture(result);
    }

    private int loadTeamColorSwatchTexture(int tcIdx) {
        BufferedImage img = GameDataSource.getInstance().loadTeamColorTexture(tcIdx, modelDir, rootDir);
        if (img != null) {
            return uploadTexture(img);
        }
        return createSolidColorTexture(resolveTeamColorRgb(tcIdx));
    }

    private int[] resolveTeamColorRgb(int tcIdx) {
        int[] sampled = GameDataSource.getInstance().loadTeamColorRgb(tcIdx, modelDir, rootDir);
        if (sampled != null) {
            return sampled;
        }
        return DEFAULT_TEAM_COLORS[Math.max(0, Math.min(DEFAULT_TEAM_COLORS.length - 1, tcIdx))];
    }

    /** Creates a 4x4 solid color GL texture. */
    private static int createSolidColorTexture(int[] rgb) {
        ByteBuffer buf = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 16; i++) {
            buf.put((byte) rgb[0]); buf.put((byte) rgb[1]); buf.put((byte) rgb[2]); buf.put((byte) 255);
        }
        buf.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    /** Re-composites and re-uploads GL textures for all geosets with team color. */
    private void reloadTeamColorTextures() {
        int tc = teamColorIdx;
        for (int gi = 0; gi < texData.length; gi++) {
            if (gi >= geoTex.length) break;
            var layers = texData[gi].layers();
            if (layers.isEmpty()) {
                // Legacy single-layer: reload if team color
                if (!texData[gi].hasTeamColor()) continue;
                if (geoTex[gi].length > 0 && geoTex[gi][0] != 0) {
                    glDeleteTextures(geoTex[gi][0]); geoTex[gi][0] = 0;
                }
                geoTex[gi][0] = loadLayerTexture(texData[gi].texturePath(), texData[gi].replaceableId(), tc);
            } else {
                for (int li = 0; li < layers.size(); li++) {
                    var layer = layers.get(li);
                    if (layer.replaceableId() != 1 && layer.replaceableId() != 2) continue;
                    if (geoTex[gi][li] != 0) { glDeleteTextures(geoTex[gi][li]); geoTex[gi][li] = 0; }
                    geoTex[gi][li] = loadLayerTexture(layer.texturePath(), layer.replaceableId(), tc);
                }
            }
        }
    }

    private int loadGlTexture(String texPath) {
        BufferedImage img = GameDataSource.getInstance().loadTexture(texPath, modelDir, rootDir);
        if (img == null) {
            System.out.println("[GL] Texture not found: " + texPath);
            return 0;
        }
        System.out.println("[GL] Loaded texture: " + texPath + " (" + img.getWidth() + "x" + img.getHeight() + ")");
        return uploadTexture(img);
    }

    static int uploadTexture(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        // For ComponentColorModel images (e.g. JPEG BLP with CS_LINEAR_RGB), read raw
        // raster data to avoid an unwanted linear→sRGB conversion in getRGB() that
        // brightens the image. IndexColorModel images still need getRGB() for palette lookup.
        boolean rawRaster = img.getColorModel() instanceof java.awt.image.ComponentColorModel;
        // Flip Y: OpenGL expects rows bottom-to-top; BufferedImage/BLP stores top-to-bottom
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                if (rawRaster) {
                    int[] pixel = img.getRaster().getPixel(x, y, (int[]) null);
                    buf.put((byte) pixel[0]);
                    buf.put((byte) pixel[1]);
                    buf.put((byte) pixel[2]);
                    buf.put((byte) (pixel.length > 3 ? pixel[3] : 255));
                } else {
                    int rgb = img.getRGB(x, y);
                    buf.put((byte) ((rgb >> 16) & 0xFF));
                    buf.put((byte) ((rgb >> 8) & 0xFF));
                    buf.put((byte) (rgb & 0xFF));
                    buf.put((byte) ((rgb >> 24) & 0xFF));
                }
            }
        }
        buf.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    private void buildBoneVao() {
        if (animData.bones().length == 0) return;
        // Only lines now (cubes are drawn separately via nodeCubeVao)
        int maxLineVerts = animData.bones().length * 2;
        boneVao = glGenVertexArrays(); boneVbo = glGenBuffers();
        glBindVertexArray(boneVao);
        glBindBuffer(GL_ARRAY_BUFFER, boneVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) maxLineVerts * 3 * 4, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    /** Unit cube centered at origin (±0.5), used for node overlays. */
    private void buildNodeCubeVao() {
        float s = 0.5f;
        float[] v = {
            -s,-s,-s, s,-s,-s, s,s,-s, -s,s,-s,
            -s,-s, s, s,-s, s, s,s, s, -s,s, s
        };
        int[] ix = {
            0,1,2,0,2,3, 4,5,6,4,6,7, 0,4,7,0,7,3,
            1,5,6,1,6,2, 0,1,5,0,5,4, 3,2,6,3,6,7
        };
        nodeCubeVao = glGenVertexArrays(); nodeCubeVbo = glGenBuffers(); nodeCubeEbo = glGenBuffers();
        glBindVertexArray(nodeCubeVao);
        glBindBuffer(GL_ARRAY_BUFFER, nodeCubeVbo);
        glBufferData(GL_ARRAY_BUFFER, v, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, nodeCubeEbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, ix, GL_STATIC_DRAW);
        glBindVertexArray(0);
    }

    /**
     * Extracts the animated world-space position of a bone node.
     * This is the bone's pivot point transformed through its world matrix.
     * Using worldMatrix × pivot (not just column 3, which is worldMatrix × origin).
     */
    private static float[] boneWorldPos(BoneNode bone, Map<Integer, float[]> worldMap) {
        float[] w = worldMap.get(bone.objectId());
        float[] p = bone.pivot();
        float px = p != null && p.length > 0 ? p[0] : 0f;
        float py = p != null && p.length > 1 ? p[1] : 0f;
        float pz = p != null && p.length > 2 ? p[2] : 0f;
        if (w == null) return new float[]{px, py, pz};
        return new float[]{
            w[0]*px + w[4]*py + w[8]*pz  + w[12],
            w[1]*px + w[5]*py + w[9]*pz  + w[13],
            w[2]*px + w[6]*py + w[10]*pz + w[14]
        };
    }

    /**
     * Draws a cube at each position using the solid shader (must be active, nodeCubeVao bound).
     * Each cube is translated+scaled via a modified MVP.
     */
    private void drawNodeCubes(float[][] positions, float[] baseMvp, float size) {
        for (float[] pos : positions) {
            // Build mvp = baseMvp × T(pos) × S(size)
            float[] m = identity();
            m[0] = size; m[5] = size; m[10] = size;
            m[12] = pos[0]; m[13] = pos[1]; m[14] = pos[2];
            float[] cubeMvp = matMul(baseMvp, m);
            glUniformMatrix4fv(solidMvp, false, cubeMvp);
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0L);
        }
    }

    /**
     * Updates the bone VBO with parent-child lines grouped by node type,
     * and caches per-type node positions for cube drawing.
     * Returns [boneLineVerts, helperLineVerts, attachLineVerts].
     */
    private int[] updateBoneVbo() {
        BoneNode[] bones = animData.bones();
        Map<Integer, float[]> worldMap;
        if (currentSeqIdx >= 0 && animData.hasAnimation()) {
            SequenceInfo seq = animData.sequences().get(currentSeqIdx);
            float[] camRot = computeCameraRotationQuat();
            worldMap = BoneAnimator.computeWorldMatrices(bones, animTimeMs, seq.start(), seq.end(), animData.globalSequences(), globalSeqTimeMs, camRot);
        } else {
            float[] camRot = computeCameraRotationQuat();
            worldMap = BoneAnimator.computeWorldMatrices(bones, 0, 0, 0, animData.globalSequences(), globalSeqTimeMs, camRot);
        }
        lastWorldMap = worldMap;

        Map<Integer, BoneNode> byId = new java.util.HashMap<>(bones.length * 2);
        for (BoneNode b : bones) byId.put(b.objectId(), b);

        float[] buf = new float[bones.length * 2 * 3];
        int li = 0;

        // Collect positions per type for cube drawing
        java.util.List<float[]> bonePosList     = new java.util.ArrayList<>();
        java.util.List<float[]> helperPosList    = new java.util.ArrayList<>();
        java.util.List<float[]> attachPosList    = new java.util.ArrayList<>();
        java.util.List<float[]> ribbonPosList    = new java.util.ArrayList<>();
        java.util.List<float[]> particlePosList  = new java.util.ArrayList<>();

        for (BoneNode bone : bones) {
            float[] pos = boneWorldPos(bone, worldMap);
            switch (bone.nodeType()) {
                case BONE              -> bonePosList.add(pos);
                case HELPER            -> helperPosList.add(pos);
                case ATTACHMENT        -> attachPosList.add(pos);
                case RIBBON_EMITTER    -> ribbonPosList.add(pos);
                case PARTICLE_EMITTER2 -> particlePosList.add(pos);
            }
        }
        bonePositions     = bonePosList.toArray(new float[0][]);
        helperPositions   = helperPosList.toArray(new float[0][]);
        attachPositions   = attachPosList.toArray(new float[0][]);
        ribbonPositions   = ribbonPosList.toArray(new float[0][]);
        particlePositions = particlePosList.toArray(new float[0][]);

        // Lines: bones, helpers, attachments, ribbons, particles
        int boneLineVerts = 0, helperLineVerts = 0, attachLineVerts = 0;
        int ribbonLineVerts = 0, particleLineVerts = 0;
        for (BoneNode.NodeType type : BoneNode.NodeType.values()) {
            for (BoneNode bone : bones) {
                if (bone.nodeType() != type) continue;
                if (bone.parentId() < 0) continue;
                BoneNode parent = byId.get(bone.parentId());
                if (parent == null) continue;
                float[] childPos  = boneWorldPos(bone, worldMap);
                float[] parentPos = boneWorldPos(parent, worldMap);
                buf[li++] = parentPos[0]; buf[li++] = parentPos[1]; buf[li++] = parentPos[2];
                buf[li++] = childPos[0];  buf[li++] = childPos[1];  buf[li++] = childPos[2];
                switch (type) {
                    case BONE              -> boneLineVerts += 2;
                    case HELPER            -> helperLineVerts += 2;
                    case ATTACHMENT        -> attachLineVerts += 2;
                    case RIBBON_EMITTER    -> ribbonLineVerts += 2;
                    case PARTICLE_EMITTER2 -> particleLineVerts += 2;
                }
            }
        }

        glBindBuffer(GL_ARRAY_BUFFER, boneVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, java.util.Arrays.copyOf(buf, li));
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        return new int[]{boneLineVerts, helperLineVerts, attachLineVerts, ribbonLineVerts, particleLineVerts};
    }

    private void buildExtentVao() {
        updateExtentVao(mesh.minX(), mesh.minY(), mesh.minZ(),
                        mesh.maxX(), mesh.maxY(), mesh.maxZ());
    }

    private void updateExtentVao(float x0, float y0, float z0,
                                  float x1, float y1, float z1) {
        // 12 edges of a box = 24 line endpoints
        float[] lines = {
            // Bottom face (4 edges)
            x0,y0,z0, x1,y0,z0,  x1,y0,z0, x1,y1,z0,  x1,y1,z0, x0,y1,z0,  x0,y1,z0, x0,y0,z0,
            // Top face (4 edges)
            x0,y0,z1, x1,y0,z1,  x1,y0,z1, x1,y1,z1,  x1,y1,z1, x0,y1,z1,  x0,y1,z1, x0,y0,z1,
            // Vertical pillars (4 edges)
            x0,y0,z0, x0,y0,z1,  x1,y0,z0, x1,y0,z1,  x1,y1,z0, x1,y1,z1,  x0,y1,z0, x0,y1,z1,
        };
        extentLineCount = 24;
        if (extentVao == 0) {
            extentVao = glGenVertexArrays();
            extentVbo = glGenBuffers();
            glBindVertexArray(extentVao);
            glBindBuffer(GL_ARRAY_BUFFER, extentVbo);
            glBufferData(GL_ARRAY_BUFFER, lines, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
            glEnableVertexAttribArray(0);
        } else {
            glBindVertexArray(extentVao);
            glBindBuffer(GL_ARRAY_BUFFER, extentVbo);
            glBufferData(GL_ARRAY_BUFFER, lines, GL_STATIC_DRAW);
        }
        glBindVertexArray(0);
    }

    private void buildCollisionVao() {
        if (collisionShapes.length == 0) return;
        java.util.List<Float> lines = new java.util.ArrayList<>();
        for (CollisionShape cs : collisionShapes) {
            switch (cs.type()) {
                case BOX -> {
                    if (cs.vertices().length >= 2) {
                        float[] v0 = cs.vertices()[0], v1 = cs.vertices()[1];
                        addBoxLines(lines, v0[0], v0[1], v0[2], v1[0], v1[1], v1[2]);
                    }
                }
                case SPHERE -> {
                    if (cs.vertices().length >= 1) {
                        float[] c = cs.vertices()[0];
                        addSphereLines(lines, c[0], c[1], c[2], cs.boundsRadius());
                    }
                }
                case CYLINDER -> {
                    // Approximate as sphere at midpoint
                    if (cs.vertices().length >= 1) {
                        float[] c = cs.vertices()[0];
                        addSphereLines(lines, c[0], c[1], c[2], cs.boundsRadius());
                    }
                }
                case PLANE -> {} // skip
            }
        }
        if (lines.isEmpty()) return;
        float[] data = new float[lines.size()];
        for (int i = 0; i < data.length; i++) data[i] = lines.get(i);
        collisionLineCount = data.length / 3;
        collisionVao = glGenVertexArrays(); collisionVbo = glGenBuffers();
        glBindVertexArray(collisionVao);
        glBindBuffer(GL_ARRAY_BUFFER, collisionVbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    private static void addBoxLines(java.util.List<Float> lines,
                                     float x0, float y0, float z0, float x1, float y1, float z1) {
        // 12 edges of a box
        float[][] corners = {
            {x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},
            {x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}
        };
        int[][] edges = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        for (int[] e : edges) {
            addLine(lines, corners[e[0]], corners[e[1]]);
        }
    }

    private static void addSphereLines(java.util.List<Float> lines,
                                        float cx, float cy, float cz, float r) {
        // 3 circles (XY, XZ, YZ planes) with 24 segments each
        int segments = 24;
        for (int ring = 0; ring < 3; ring++) {
            for (int i = 0; i < segments; i++) {
                double a0 = 2 * Math.PI * i / segments;
                double a1 = 2 * Math.PI * (i + 1) / segments;
                float c0 = (float) Math.cos(a0) * r, s0 = (float) Math.sin(a0) * r;
                float c1 = (float) Math.cos(a1) * r, s1 = (float) Math.sin(a1) * r;
                switch (ring) {
                    case 0 -> { // XY plane
                        addLine(lines, new float[]{cx+c0,cy+s0,cz}, new float[]{cx+c1,cy+s1,cz});
                    }
                    case 1 -> { // XZ plane
                        addLine(lines, new float[]{cx+c0,cy,cz+s0}, new float[]{cx+c1,cy,cz+s1});
                    }
                    case 2 -> { // YZ plane
                        addLine(lines, new float[]{cx,cy+c0,cz+s0}, new float[]{cx,cy+c1,cz+s1});
                    }
                }
            }
        }
    }

    private static void addLine(java.util.List<Float> lines, float[] a, float[] b) {
        lines.add(a[0]); lines.add(a[1]); lines.add(a[2]);
        lines.add(b[0]); lines.add(b[1]); lines.add(b[2]);
    }

    private void buildCubeVao() {
        float s=40f;
        float[] v={-s,-s,-s,s,-s,-s,s,s,-s,-s,s,-s,-s,-s,s,s,-s,s,s,s,s,-s,s,s};
        int[]  ix={0,1,2,0,2,3,4,5,6,4,6,7,0,4,7,0,7,3,1,5,6,1,6,2,0,1,5,0,5,4,3,2,6,3,6,7};
        cubeVao=glGenVertexArrays();cubeVbo=glGenBuffers();cubeEbo=glGenBuffers();
        glBindVertexArray(cubeVao);glBindBuffer(GL_ARRAY_BUFFER,cubeVbo);glBufferData(GL_ARRAY_BUFFER,v,GL_STATIC_DRAW);
        glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,cubeEbo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,ix,GL_STATIC_DRAW);glBindVertexArray(0);
    }

    // ── Matrix math ──────────────────────────────────────────────────────────

    static float[] buildProjection(float fov, float asp, float near, float far) {
        float f=(float)(1.0/Math.tan(Math.toRadians(fov)*0.5)), ri=1f/(near-far);
        return new float[]{f/asp,0,0,0,0,f,0,0,0,0,(near+far)*ri,-1,0,0,2*near*far*ri,0};
    }
    /** Camera orbit transform — used for the grid and fallback cube (already Y-up). */
    private float[] buildCameraView() {
        float[] m=identity();
        m=translate(m,panX,panY,-distance); m=rotateX(m,pitchDegrees); m=rotateY(m,yawDegrees);
        return m;
    }
    /** Camera orbit + Z-up→Y-up conversion + model centering/scaling. */
    private float[] buildModelView() {
        float[] m=buildCameraView();
        if(hasRenderableMesh() || animData.hasAnimation()){
            m=rotateX(m,-90f); // WC3 Z-up → OpenGL Y-up
            m=scale(m,modelScale);
            m=translate(m,-frameCenterX,-frameCenterY,-frameCenterZ);
        }
        return m;
    }
    static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    static float[] translate(float[] m,float tx,float ty,float tz){float[] t=identity();t[12]=tx;t[13]=ty;t[14]=tz;return matMul(m,t);}
    static float[] rotateX(float[] m,float d){float c=(float)Math.cos(Math.toRadians(d)),s=(float)Math.sin(Math.toRadians(d));float[] r=identity();r[5]=c;r[6]=s;r[9]=-s;r[10]=c;return matMul(m,r);}
    static float[] rotateY(float[] m,float d){float c=(float)Math.cos(Math.toRadians(d)),s=(float)Math.sin(Math.toRadians(d));float[] r=identity();r[0]=c;r[2]=-s;r[8]=s;r[10]=c;return matMul(m,r);}
    static float[] scale(float[] m,float s){float[] sc=identity();sc[0]=s;sc[5]=s;sc[10]=s;return matMul(m,sc);}
    static float[] matMul(float[] a,float[] b){float[] r=new float[16];for(int row=0;row<4;row++)for(int col=0;col<4;col++){float s=0;for(int k=0;k<4;k++)s+=a[row+k*4]*b[k+col*4];r[row+col*4]=s;}return r;}
    private static float[] normalize(float x, float y, float z) { float l=(float)Math.sqrt(x*x+y*y+z*z); return l>0?new float[]{x/l,y/l,z/l}:new float[]{0,0,1}; }
    private static float[] transformDirByMat(float[] m, float x, float y, float z) { float rx=m[0]*x+m[4]*y+m[8]*z, ry=m[1]*x+m[5]*y+m[9]*z, rz=m[2]*x+m[6]*y+m[10]*z; float l=(float)Math.sqrt(rx*rx+ry*ry+rz*rz); return l>0?new float[]{rx/l,ry/l,rz/l}:new float[]{0,0,1}; }

    // ── Camera ───────────────────────────────────────────────────────────────

    private boolean hasRenderableMesh() {
        return mesh != null && !mesh.isEmpty()
            && Float.isFinite(mesh.radius()) && mesh.radius() > 0.0001f;
    }
    /**
     * Computes camera framing à la Retera's Model Studio:
     * distance = boundsRadius * sqrt(2) * 2, target Z = boundsRadius / 2.
     * Uses "Stand" sequence extents when available, falls back to vertex AABB.
     */
    private void applyInitialCameraDistance() {
        if (hasRenderableMesh()) {
            float boundsRadius = resolveInitialBoundsRadius();
            // Use vertex AABB center (already computed by resolveInitialBoundsRadius)
            frameToBounds(boundsRadius, vertexAABBCenterX, vertexAABBCenterY, vertexAABBCenterZ);
        } else if (animData.hasAnimation()) {
            float boundsRadius = computePivotBoundsRadius();
            frameToBounds(Math.max(boundsRadius, 100f), 0f, 0f, 0f);
        } else {
            modelScale = 1f; distance = 300f; panX = 0f; panY = 0f;
        }
    }

    private void frameToBounds(float boundsRadius, float cx, float cy, float cz) {
        frameCenterX = cx;
        frameCenterY = cy;
        frameCenterZ = cz;
        modelScale = clamp(120f / boundsRadius, 0.005f, 500f);
        float scaledRadius = boundsRadius * modelScale;
        distance = clamp(scaledRadius * (float) Math.sqrt(2) * 2f,
                MIN_DISTANCE, MAX_DISTANCE);
        panX = 0f;
        panY = 0f;
    }

    /** Compute bounds radius from bone/node pivot points (for mesh-less models). */
    private float computePivotBoundsRadius() {
        BoneNode[] bones = animData.bones();
        if (bones == null || bones.length == 0) return 100f;
        float maxR = 0f;
        for (BoneNode b : bones) {
            float[] p = b.pivot();
            if (p == null || p.length < 3) continue;
            float r = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            maxR = Math.max(maxR, r);
        }
        return maxR > 0.001f ? maxR * 1.5f : 100f;
    }

    /** Reframe the camera to fit the given sequence's bounding volume, resetting angles. */
    public void reframeToSequence(SequenceInfo seq) {
        // Compute animated AABB for the sequence to populate vertexAABBCenter fields
        if (hasRenderableMesh()) {
            computeVertexBoundsRadius(seq);
        }
        float boundsRadius;
        if (seq != null && seq.boundsRadius() > 1f) {
            boundsRadius = seq.boundsRadius();
        } else if (seq != null && seq.hasExtent() && seq.extentRadius() > 0.001f) {
            boundsRadius = seq.extentRadius();
        } else if (hasRenderableMesh()) {
            boundsRadius = computeVertexBoundsRadius(seq);
        } else {
            boundsRadius = computePivotBoundsRadius();
        }
        boundsRadius = clamp(boundsRadius, 0.1f, 10000f);
        frameToBounds(boundsRadius, vertexAABBCenterX, vertexAABBCenterY, vertexAABBCenterZ);
        yawDegrees = initialYaw;
        pitchDegrees = initialPitch;
    }

    /**
     * Resolve the initial bounds radius using Retera's priority:
     * "Stand" sequence boundsRadius > "Stand" extent diagonal > vertex AABB > fallback 64.
     */
    private float resolveInitialBoundsRadius() {
        // Always compute vertex AABB to populate center fields
        float vertR = computeVertexBoundsRadius();
        // Try "Stand" animation extents for the radius
        if (animData != null && animData.sequences() != null) {
            for (SequenceInfo seq : animData.sequences()) {
                if (seq.name().toLowerCase(java.util.Locale.ROOT).contains("stand")) {
                    if (seq.boundsRadius() > 1f) return seq.boundsRadius();
                    if (seq.hasExtent() && seq.extentRadius() > 0.001f) return seq.extentRadius();
                }
            }
        }
        return vertR > 0.1f ? vertR : 64f;
    }

    /** Compute AABB from vertex positions (animated if possible), store center, return half-diagonal radius. */
    private float computeVertexBoundsRadius() {
        return computeVertexBoundsRadius(null);
    }

    /**
     * Compute AABB from vertex positions, applying skeletal animation from the given sequence
     * (or the "Stand" sequence if seq is null). This matches the thumbnail renderer's camera framing.
     */
    private float computeVertexBoundsRadius(SequenceInfo seq) {
        float[] verts = mesh.vertices();
        if (verts.length < 3) return Math.max(30f, mesh.radius());

        // Find a sequence for animated pose: use provided seq, or find "Stand"
        SequenceInfo poseSeq = seq;
        if (poseSeq == null && animData != null && animData.sequences() != null) {
            for (SequenceInfo s : animData.sequences()) {
                if (s.name().toLowerCase(java.util.Locale.ROOT).contains("stand")) {
                    poseSeq = s;
                    break;
                }
            }
        }

        // Compute bone matrices for animation pose
        Map<Integer, float[]> boneMatrices = null;
        if (poseSeq != null && animData != null && animData.bones() != null) {
            boneMatrices = BoneAnimator.computeWorldMatrices(
                    animData.bones(), poseSeq.start(), poseSeq.start(), poseSeq.end(), animData.globalSequences());
        }

        // Sample per-geoset alpha to skip hidden geosets (matching thumbnail renderer)
        java.util.List<GeosetSkinData> geosets = animData != null ? animData.geosets() : java.util.List.of();
        float[] geoAlpha = new float[geosets.size()];
        java.util.Arrays.fill(geoAlpha, 1.0f);
        if (poseSeq != null && animData != null) {
            long[] globalSeqs = animData.globalSequences();
            for (int i = 0; i < geosets.size(); i++) {
                AnimTrack track = animData.geosetAlpha().get(i);
                if (track != null && !track.isEmpty()) {
                    float val = BoneAnimator.interpTrackScalar(track, poseSeq.start(),
                            poseSeq.start(), poseSeq.end(), globalSeqs, 1f);
                    geoAlpha[i] = Math.max(0f, Math.min(1f, val));
                }
            }
        }

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        if (boneMatrices != null && !geosets.isEmpty()) {
            // Animated AABB: transform vertices by bone matrices per geoset (same as thumbnail renderer)
            int vertOffset = 0;
            for (int gi = 0; gi < geosets.size(); gi++) {
                GeosetSkinData skin = geosets.get(gi);
                int vc = skin.vertexCount();
                if (vc == 0) { vertOffset += vc; continue; }
                if (geoAlpha[gi] <= 0f) { vertOffset += vc; continue; }

                for (int vi = 0; vi < vc; vi++) {
                    float vx, vy, vz;
                    if (skin.hasSkinning()) {
                        float[] p = transformVertex(skin, vi, boneMatrices);
                        vx = p[0]; vy = p[1]; vz = p[2];
                    } else {
                        int idx = (vertOffset + vi) * 3;
                        vx = verts[idx]; vy = verts[idx + 1]; vz = verts[idx + 2];
                    }
                    minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
                    minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
                    minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
                }
                vertOffset += vc;
            }
        }

        // Fallback to static vertices if no animated bounds were computed
        if (minX > maxX) {
            for (int i = 0; i < verts.length; i += 3) {
                minX = Math.min(minX, verts[i]);     maxX = Math.max(maxX, verts[i]);
                minY = Math.min(minY, verts[i + 1]); maxY = Math.max(maxY, verts[i + 1]);
                minZ = Math.min(minZ, verts[i + 2]); maxZ = Math.max(maxZ, verts[i + 2]);
            }
        }

        vertexAABBCenterX = (minX + maxX) * 0.5f;
        vertexAABBCenterY = (minY + maxY) * 0.5f;
        vertexAABBCenterZ = (minZ + maxZ) * 0.5f;
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return Math.max(30f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5f);
    }

    // ── Input ────────────────────────────────────────────────────────────────

    private void installInputHandlers() {
        java.awt.event.MouseAdapter a = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                requestFocusInWindow(); lastMouseX=e.getX(); lastMouseY=e.getY();
                draggingOrbit=SwingUtilities.isLeftMouseButton(e)&&!e.isShiftDown();
                draggingPan=SwingUtilities.isRightMouseButton(e)||(SwingUtilities.isLeftMouseButton(e)&&e.isShiftDown());
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e){draggingOrbit=false;draggingPan=false;}
            @Override public void mouseDragged(java.awt.event.MouseEvent e){
                int dx=e.getX()-lastMouseX,dy=e.getY()-lastMouseY;lastMouseX=e.getX();lastMouseY=e.getY();
                if(draggingOrbit){
                    yawDegrees+=dx*0.5f;pitchDegrees=clamp(pitchDegrees+dy*0.4f,-89,89);
                    if(cameraOrbitListener!=null)cameraOrbitListener.run();
                }
                else if(draggingPan){float sc=Math.max(0.05f,distance/900f);panX+=dx*sc;panY-=dy*sc;}
            }
            @Override public void mouseClicked(java.awt.event.MouseEvent e){if(SwingUtilities.isRightMouseButton(e)&&e.getClickCount()==2)resetCamera();}
        };
        addMouseListener(a); addMouseMotionListener(a);
        addMouseWheelListener(e->{distance=clamp(distance*(e.getWheelRotation()>0?1.08f:0.92f),MIN_DISTANCE,MAX_DISTANCE);});
        addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                int seqCount = animData.sequences().size();
                if (seqCount == 0) return;
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_RIGHT || e.getKeyCode() == java.awt.event.KeyEvent.VK_DOWN) {
                    setSequence((currentSeqIdx + 1) % seqCount);
                    setPlaying(true);
                } else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_LEFT || e.getKeyCode() == java.awt.event.KeyEvent.VK_UP) {
                    setSequence((currentSeqIdx - 1 + seqCount) % seqCount);
                    setPlaying(true);
                }
            }
        });
    }
    /** Sets the default camera angles used on reset and initial view. */
    public void setInitialCamera(float yaw, float pitch) {
        this.initialYaw = yaw;
        this.initialPitch = pitch;
        this.yawDegrees = yaw;
        this.pitchDegrees = pitch;
    }

    public float getYawDegrees() { return yawDegrees; }
    public float getPitchDegrees() { return pitchDegrees; }

    /** Fires after a left-mouse-button orbit drag changes yaw/pitch. */
    public void setCameraOrbitListener(Runnable listener) {
        this.cameraOrbitListener = listener;
    }

    private void resetCamera(){yawDegrees=initialYaw;pitchDegrees=initialPitch;panX=0f;panY=0f;applyInitialCameraDistance();}
    static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    // ── Ribbon emitter simulation & rendering ────────────────────────────────

    /** Runtime state for one ribbon emitter (particle trail). */
    private static final class RibbonState {
        final RibbonEmitterData data;
        final float[] pivot;      // emitter node's pivot point [x,y,z]
        final float[][] abovePos; // ring buffer: world-space "above" edge [maxParticles][3]
        final float[][] belowPos; // ring buffer: world-space "below" edge [maxParticles][3]
        final float[][] velocity; // ring buffer: per-particle velocity [maxParticles][3]
        final float[] health;     // ring buffer: remaining lifetime (seconds)
        final float[] uvU;        // ring buffer: U texture coordinate
        final float[] uvV;        // ring buffer: V top texture coordinate
        final float[] uvV2;       // ring buffer: V bottom texture coordinate
        int head = 0;             // next write position (newest particle)
        int count = 0;            // number of alive particles
        float emission = 0f;      // fractional emission accumulator

        RibbonState(RibbonEmitterData data, float[] pivot) {
            this.data = data;
            this.pivot = pivot;
            int maxParticles = Math.max(4, (int)(data.lifeSpan() * data.emissionRate()) + 2);
            abovePos = new float[maxParticles][3];
            belowPos = new float[maxParticles][3];
            velocity = new float[maxParticles][3];
            health = new float[maxParticles];
            uvU = new float[maxParticles];
            uvV = new float[maxParticles];
            uvV2 = new float[maxParticles];
        }

        int capacity() { return health.length; }

        /** Index of the oldest alive particle (tail of ring buffer). */
        int tail() { return (head - count + capacity()) % capacity(); }

        void reset() {
            head = 0; count = 0; emission = 0f;
            for (float[] v : velocity) java.util.Arrays.fill(v, 0f);
        }
    }

    private void initRibbons() {
        if (ribbonEmitters.length == 0) return;

        ribbonStates = new RibbonState[ribbonEmitters.length];
        ribbonTextures = new int[ribbonEmitters.length];
        ribbonFilterModes = new int[ribbonEmitters.length];

        // Build objectId → pivot lookup from bones
        java.util.Map<Integer, float[]> pivotMap = new java.util.HashMap<>();
        for (BoneNode bone : animData.bones()) {
            pivotMap.put(bone.objectId(), bone.pivot());
        }

        for (int i = 0; i < ribbonEmitters.length; i++) {
            float[] pivot = pivotMap.getOrDefault(ribbonEmitters[i].objectId(), new float[3]);
            ribbonStates[i] = new RibbonState(ribbonEmitters[i], pivot);
            // Resolve texture from material
            int matId = ribbonEmitters[i].materialId();
            if (matId >= 0 && matId < materials.length && !materials[matId].layers().isEmpty()) {
                MaterialInfo.LayerInfo layer = materials[matId].layers().get(0);
                ribbonFilterModes[i] = layer.filterMode();
                String texPath = layer.texturePath();
                if (texPath != null && !texPath.isBlank() && modelDir != null) {
                    ribbonTextures[i] = loadGlTexture(texPath, layer.replaceableId());
                }
            }
        }

        // Compile ribbon shader
        ribbonShader = linkProgram(RIBBON_VERT, RIBBON_FRAG);
        if (ribbonShader != 0) {
            ribbonMvp = glGetUniformLocation(ribbonShader, "mvp");
            ribbonSampler = glGetUniformLocation(ribbonShader, "tex");
            ribbonHasTex = glGetUniformLocation(ribbonShader, "uHasTex");
            ribbonAlphaU = glGetUniformLocation(ribbonShader, "uAlpha");
        }

        // Create dynamic VAO/VBO for ribbon geometry
        ribbonVao = glGenVertexArrays();
        ribbonVbo = glGenBuffers();
        glBindVertexArray(ribbonVao);
        glBindBuffer(GL_ARRAY_BUFFER, ribbonVbo);
        // Pre-allocate buffer (pos=3 + uv=2 + color=3 = 8 floats per vertex)
        glBufferData(GL_ARRAY_BUFFER, (long) RIBBON_MAX_VERTS * RIBBON_FLOATS_PER_VERT * 4, GL_DYNAMIC_DRAW);
        // Position at location 0
        glVertexAttribPointer(0, 3, GL_FLOAT, false, RIBBON_FLOATS_PER_VERT * 4, 0L);
        glEnableVertexAttribArray(0);
        // UV at location 1
        glVertexAttribPointer(1, 2, GL_FLOAT, false, RIBBON_FLOATS_PER_VERT * 4, 3L * 4);
        glEnableVertexAttribArray(1);
        // Color at location 2
        glVertexAttribPointer(2, 3, GL_FLOAT, false, RIBBON_FLOATS_PER_VERT * 4, 5L * 4);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
    }

    private int loadGlTexture(String texPath, int replaceableId) {
        GameDataSource gds = GameDataSource.getInstance();
        java.awt.image.BufferedImage img = gds.loadTexture(texPath, modelDir, rootDir);
        if (img == null) return 0;
        return uploadTexture(img);
    }

    private void simulateRibbons(float dt) {
        if (ribbonStates == null || dt <= 0f) return;

        Map<Integer, float[]> wm = lastWorldMap;
        if (wm == null) return;

        SequenceInfo seq = (currentSeqIdx >= 0 && currentSeqIdx < animData.sequences().size())
                ? animData.sequences().get(currentSeqIdx) : null;
        long[] globalSeqs = animData.globalSequences();

        for (RibbonState rs : ribbonStates) {
            RibbonEmitterData rd = rs.data;
            float[] matrix = wm.get(rd.objectId());
            if (matrix == null) continue;

            // Get animated values
            float gravity = rd.gravity();

            // Update existing particles: aging and gravity always run regardless of visibility
            int cap = rs.capacity();
            for (int pi = 0; pi < rs.count; pi++) {
                int idx = (rs.tail() + pi) % cap;
                rs.health[idx] -= dt;
                if (rs.health[idx] <= 0) continue;
                // Accumulate gravity in velocity (Z-axis downward in WC3 Z-up space)
                rs.velocity[idx][2] -= gravity * dt;
                // Apply velocity to both edges
                float vx = rs.velocity[idx][0] * dt;
                float vy = rs.velocity[idx][1] * dt;
                float vz = rs.velocity[idx][2] * dt;
                rs.abovePos[idx][0] += vx; rs.abovePos[idx][1] += vy; rs.abovePos[idx][2] += vz;
                rs.belowPos[idx][0] += vx; rs.belowPos[idx][1] += vy; rs.belowPos[idx][2] += vz;
            }

            // Remove dead particles from tail
            while (rs.count > 0) {
                int tailIdx = rs.tail();
                if (rs.health[tailIdx] > 0) break;
                rs.count--;
            }

            // Visibility and user toggle gate emission, not aging/removal
            float vis = isEmitterEnabled(rd.objectId())
                    ? interpolateScalar(rd.visibilityTrack(), seq, globalSeqs, 1f) : 0f;

            // Spawn new particle (at most one per frame, like WC3) — only when visible
            rs.emission += vis > 0f ? rd.emissionRate() * dt : 0f;
            if (rs.emission >= 1f) {
                rs.emission = rs.emission % 1f;
                float hAbove = interpolateScalar(rd.heightAboveTrack(), seq, globalSeqs, rd.heightAbove());
                float hBelow = interpolateScalar(rd.heightBelowTrack(), seq, globalSeqs, rd.heightBelow());
                float[] pivot = rs.pivot;
                float[] above = transformPoint(matrix, pivot[0], pivot[1] + hAbove, pivot[2]);
                float[] below = transformPoint(matrix, pivot[0], pivot[1] - hBelow, pivot[2]);

                int slot = rs.head;
                rs.abovePos[slot][0] = above[0]; rs.abovePos[slot][1] = above[1]; rs.abovePos[slot][2] = above[2];
                rs.belowPos[slot][0] = below[0]; rs.belowPos[slot][1] = below[1]; rs.belowPos[slot][2] = below[2];
                rs.velocity[slot][0] = 0f; rs.velocity[slot][1] = 0f; rs.velocity[slot][2] = 0f;
                rs.health[slot] = rd.lifeSpan();
                rs.uvU[slot] = 0f;
                rs.head = (rs.head + 1) % cap;
                if (rs.count < cap) rs.count++;
            }

            // Update UV coordinates based on particle age ratio (like war3-model reference)
            float lifeSpan = rd.lifeSpan();
            if (lifeSpan <= 0f) lifeSpan = 1f;
            int columns = Math.max(1, rd.columns());
            int rows = Math.max(1, rd.rows());
            int texSlot = rd.textureSlot();
            // Animated texture slot
            if (rd.texSlotTrack() != null && !rd.texSlotTrack().isEmpty()) {
                texSlot = (int) interpolateScalar(rd.texSlotTrack(), seq, globalSeqs, texSlot);
            }
            int texCoordX = texSlot % columns;
            int texCoordY = texSlot / rows;
            float cellWidth = 1f / columns;
            float cellHeight = 1f / rows;

            for (int pi = 0; pi < rs.count; pi++) {
                int idx = (rs.tail() + pi) % cap;
                // relativePos = age / lifeSpan (0 at birth, 1 at death)
                float relativePos = 1f - (rs.health[idx] / lifeSpan);
                rs.uvU[idx] = texCoordX * cellWidth + relativePos * cellWidth;
                rs.uvV[idx] = texCoordY * cellHeight;
                rs.uvV2[idx] = (1 + texCoordY) * cellHeight;
            }
        }
    }

    /** Transform a local-space point by a 4x4 column-major matrix. */
    private static float[] transformPoint(float[] m, float x, float y, float z) {
        return new float[]{
            m[0]*x + m[4]*y + m[8]*z  + m[12],
            m[1]*x + m[5]*y + m[9]*z  + m[13],
            m[2]*x + m[6]*y + m[10]*z + m[14]
        };
    }

    /** Interpolate a scalar animation track at the current animation time. */
    private float interpolateScalar(AnimTrack track, SequenceInfo seq, long[] globalSeqs, float defaultVal) {
        if (track == null || track.isEmpty()) return defaultVal;
        if (seq == null && !track.isGlobal()) return defaultVal;
        long start = seq != null ? seq.start() : 0;
        long end = seq != null ? seq.end() : 0;
        return BoneAnimator.interpTrackScalar(track, animTimeMs, start, end, globalSeqs, globalSeqTimeMs, defaultVal);
    }

    private int buildRibbonGeometry(float[] buf) {
        if (ribbonStates == null) return 0;
        int vi = 0; // index into buf (in floats)
        int maxFloats = RIBBON_MAX_VERTS * RIBBON_FLOATS_PER_VERT;

        SequenceInfo seq = (currentSeqIdx >= 0 && currentSeqIdx < animData.sequences().size())
                ? animData.sequences().get(currentSeqIdx) : null;
        long[] globalSeqs = animData.globalSequences();

        for (RibbonState rs : ribbonStates) {
            if (rs.count < 2) continue;
            RibbonEmitterData rd = rs.data;

            // Get ribbon color (already RGB after parser's readInvFloat32Array)
            float[] color = rd.color();
            float cr = color.length >= 3 ? color[0] : 1f;
            float cg = color.length >= 3 ? color[1] : 1f;
            float cb = color.length >= 3 ? color[2] : 1f;

            // Override with animated color if present
            if (rd.colorTrack() != null && !rd.colorTrack().isEmpty()) {
                float[] animated = interpolateVec3(rd.colorTrack(), seq, globalSeqs, cr, cg, cb);
                if (animated != null) { cr = animated[0]; cg = animated[1]; cb = animated[2]; }
            }

            int cap = rs.capacity();
            // Walk from oldest to newest, building quads between consecutive particles
            for (int pi = 0; pi < rs.count - 1; pi++) {
                int i0 = (rs.tail() + pi) % cap;
                int i1 = (rs.tail() + pi + 1) % cap;
                if (rs.health[i0] <= 0 || rs.health[i1] <= 0) continue;
                if (vi + 6 * RIBBON_FLOATS_PER_VERT > maxFloats) break;

                float u0 = rs.uvU[i0], u1 = rs.uvU[i1];
                float v0top = rs.uvV[i0], v0bot = rs.uvV2[i0];
                float v1top = rs.uvV[i1], v1bot = rs.uvV2[i1];
                // Triangle 1: above0, below0, above1
                vi = addRibbonVert(buf, vi, rs.abovePos[i0], u0, v0top, cr, cg, cb);
                vi = addRibbonVert(buf, vi, rs.belowPos[i0], u0, v0bot, cr, cg, cb);
                vi = addRibbonVert(buf, vi, rs.abovePos[i1], u1, v1top, cr, cg, cb);
                // Triangle 2: below0, below1, above1
                vi = addRibbonVert(buf, vi, rs.belowPos[i0], u0, v0bot, cr, cg, cb);
                vi = addRibbonVert(buf, vi, rs.belowPos[i1], u1, v1bot, cr, cg, cb);
                vi = addRibbonVert(buf, vi, rs.abovePos[i1], u1, v1top, cr, cg, cb);
            }
        }
        return vi / RIBBON_FLOATS_PER_VERT; // vertex count
    }

    private static int addRibbonVert(float[] buf, int off, float[] pos, float u, float v, float r, float g, float b) {
        buf[off++] = pos[0]; buf[off++] = pos[1]; buf[off++] = pos[2]; // position
        buf[off++] = u; buf[off++] = v;                                 // UV
        buf[off++] = r; buf[off++] = g; buf[off++] = b;                 // color
        return off;
    }

    private float[] interpolateVec3(AnimTrack track, SequenceInfo seq, long[] globalSeqs, float dr, float dg, float db) {
        if (track == null || track.isEmpty()) return null;
        if (seq == null && !track.isGlobal()) return null;
        long start = seq != null ? seq.start() : 0;
        long end = seq != null ? seq.end() : 0;
        return BoneAnimator.interpTrackVec3(track, animTimeMs, start, end, globalSeqs, globalSeqTimeMs, dr, dg, db);
    }

    private void drawRibbons(float[] mvp) {
        if (ribbonStates == null || ribbonShader == 0) return;

        // Build geometry into CPU buffer
        float[] buf = new float[RIBBON_MAX_VERTS * RIBBON_FLOATS_PER_VERT];
        int vertexCount = buildRibbonGeometry(buf);
        if (vertexCount == 0) return;

        // Upload to VBO
        glBindBuffer(GL_ARRAY_BUFFER, ribbonVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, java.util.Arrays.copyOf(buf, vertexCount * RIBBON_FLOATS_PER_VERT));

        glUseProgram(ribbonShader);
        glUniformMatrix4fv(ribbonMvp, false, mvp);
        if (ribbonSampler >= 0) glUniform1i(ribbonSampler, 0);

        glEnable(GL_BLEND);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);

        SequenceInfo seq = (currentSeqIdx >= 0 && currentSeqIdx < animData.sequences().size())
                ? animData.sequences().get(currentSeqIdx) : null;
        long[] globalSeqs = animData.globalSequences();

        // Draw each ribbon's portion of the buffer
        int offset = 0;
        for (int ri = 0; ri < ribbonStates.length; ri++) {
            RibbonState rs = ribbonStates[ri];
            if (rs.count < 2) continue;

            // Count vertices for this ribbon
            int ribbonVerts = 0;
            int cap = rs.capacity();
            for (int pi = 0; pi < rs.count - 1; pi++) {
                int i0 = (rs.tail() + pi) % cap;
                int i1 = (rs.tail() + pi + 1) % cap;
                if (rs.health[i0] > 0 && rs.health[i1] > 0) ribbonVerts += 6;
            }
            if (ribbonVerts == 0) continue;

            // Set alpha
            float alpha = interpolateScalar(rs.data.alphaTrack(), seq, globalSeqs, rs.data.alpha());
            if (ribbonAlphaU >= 0) glUniform1f(ribbonAlphaU, alpha);

            // Bind texture
            boolean hasTex = (ribbonTextures != null && ribbonTextures[ri] != 0);
            if (ribbonHasTex >= 0) glUniform1i(ribbonHasTex, hasTex ? 1 : 0);
            if (hasTex) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, ribbonTextures[ri]);
            }

            // Set blend mode based on filter mode
            int fm = (ribbonFilterModes != null) ? ribbonFilterModes[ri] : 0;
            applyRibbonBlendMode(fm);

            glBindVertexArray(ribbonVao);
            glDrawArrays(GL_TRIANGLES, offset, ribbonVerts);
            offset += ribbonVerts;
        }

        glBindVertexArray(0);
        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
    }

    private static void applyRibbonBlendMode(int filterMode) {
        switch (filterMode) {
            case 0, 1 -> glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // opaque/transparent
            case 2    -> glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // blend
            case 3    -> glBlendFunc(GL_SRC_ALPHA, GL_ONE);                 // additive
            case 4    -> glBlendFunc(GL_SRC_ALPHA, GL_ONE);                 // add alpha
            case 5    -> glBlendFunc(GL_ZERO, GL_SRC_COLOR);                // modulate
            case 6    -> glBlendFunc(GL_DST_COLOR, GL_SRC_COLOR);           // modulate 2x
            default   -> glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    private void resetRibbons() {
        if (ribbonStates == null) return;
        for (RibbonState rs : ribbonStates) rs.reset();
    }

    // ── Particle Emitter 2 ──────────────────────────────────────────────────

    private static final class Particle2State {
        final ParticleEmitter2Data data;
        final float[] pivot;        // emitter node's pivot [x,y,z]
        final float[][] pos;        // ring buffer: position [maxP][3] (world or local depending on modelSpace)
        final float[][] vel;        // ring buffer: velocity [maxP][3]
        final float[] age;          // ring buffer: remaining health (seconds, counts down)
        final boolean[] isHead;     // ring buffer: true=head particle, false=tail particle
        final float[][] nodeScale;  // ring buffer: world scale at spawn time [maxP][3]
        int head = 0;               // next write position
        int count = 0;              // alive particles
        float emission = 0f;        // fractional accumulator
        float lastEmissionRate = -1f; // for squirt mode

        Particle2State(ParticleEmitter2Data data, float[] pivot) {
            this.data = data;
            this.pivot = pivot;
            // Both head and tail particles share the pool when headOrTail==2
            int factor = (data.headOrTail() == 2) ? 2 : 1;
            int maxP = Math.max(8, (int)(data.lifeSpan() * data.emissionRate() * factor) + 8);
            pos = new float[maxP][3];
            vel = new float[maxP][3];
            age = new float[maxP];
            isHead = new boolean[maxP];
            nodeScale = new float[maxP][3];
        }

        int capacity() { return age.length; }
        int tail() { return (head - count + capacity()) % capacity(); }

        void reset() {
            head = 0; count = 0; emission = 0f; lastEmissionRate = -1f;
            for (float[] v : vel) java.util.Arrays.fill(v, 0f);
            java.util.Arrays.fill(age, 0f);
        }
    }

    private void initParticles2() {
        if (particleEmitters2.length == 0) return;

        particle2States = new Particle2State[particleEmitters2.length];
        particle2Textures = new int[particleEmitters2.length];

        // Build objectId → pivot lookup
        java.util.Map<Integer, float[]> pivotMap = new java.util.HashMap<>();
        for (BoneNode bone : animData.bones()) {
            pivotMap.put(bone.objectId(), bone.pivot());
        }

        for (int i = 0; i < particleEmitters2.length; i++) {
            float[] pivot = pivotMap.getOrDefault(particleEmitters2[i].objectId(), new float[3]);
            particle2States[i] = new Particle2State(particleEmitters2[i], pivot);
            // Resolve texture
            String texPath = particleEmitters2[i].texturePath();
            if (texPath != null && !texPath.isBlank() && modelDir != null) {
                particle2Textures[i] = loadGlTexture(texPath, particleEmitters2[i].replaceableId());
            }
        }

        // Compile particle shader
        particle2Shader = linkProgram(PARTICLE2_VERT, PARTICLE2_FRAG);
        if (particle2Shader != 0) {
            particle2Mvp = glGetUniformLocation(particle2Shader, "mvp");
            particle2Sampler = glGetUniformLocation(particle2Shader, "tex");
            particle2HasTex = glGetUniformLocation(particle2Shader, "uHasTex");
        }

        // Create dynamic VAO/VBO
        particle2Vao = glGenVertexArrays();
        particle2Vbo = glGenBuffers();
        glBindVertexArray(particle2Vao);
        glBindBuffer(GL_ARRAY_BUFFER, particle2Vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) P2_MAX_VERTS * P2_FLOATS_PER_VERT * 4, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, P2_FLOATS_PER_VERT * 4, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, P2_FLOATS_PER_VERT * 4, 3L * 4);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, P2_FLOATS_PER_VERT * 4, 5L * 4);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
    }

    /** Extract world scale from a 4x4 column-major matrix (lengths of basis columns). */
    private static float[] extractWorldScale(float[] m) {
        float sx = (float) Math.sqrt(m[0]*m[0] + m[1]*m[1] + m[2]*m[2]);
        float sy = (float) Math.sqrt(m[4]*m[4] + m[5]*m[5] + m[6]*m[6]);
        float sz = (float) Math.sqrt(m[8]*m[8] + m[9]*m[9] + m[10]*m[10]);
        return new float[]{sx, sy, sz};
    }

    /** Extract world rotation quaternion from a 4x4 column-major matrix (assumes orthogonal). */
    private static float[] extractWorldRotation(float[] m) {
        // Remove scale from basis vectors
        float sx = (float) Math.sqrt(m[0]*m[0] + m[1]*m[1] + m[2]*m[2]);
        float sy = (float) Math.sqrt(m[4]*m[4] + m[5]*m[5] + m[6]*m[6]);
        float sz = (float) Math.sqrt(m[8]*m[8] + m[9]*m[9] + m[10]*m[10]);
        if (sx < 0.0001f) sx = 1f;
        if (sy < 0.0001f) sy = 1f;
        if (sz < 0.0001f) sz = 1f;
        float r00 = m[0]/sx, r10 = m[1]/sx, r20 = m[2]/sx;
        float r01 = m[4]/sy, r11 = m[5]/sy, r21 = m[6]/sy;
        float r02 = m[8]/sz, r12 = m[9]/sz, r22 = m[10]/sz;
        // Convert rotation matrix to quaternion [x,y,z,w]
        float tr = r00 + r11 + r22;
        float qx, qy, qz, qw;
        if (tr > 0) {
            float s = (float) Math.sqrt(tr + 1.0) * 2f;
            qw = 0.25f * s; qx = (r21-r12)/s; qy = (r02-r20)/s; qz = (r10-r01)/s;
        } else if (r00 > r11 && r00 > r22) {
            float s = (float) Math.sqrt(1.0 + r00 - r11 - r22) * 2f;
            qw = (r21-r12)/s; qx = 0.25f * s; qy = (r01+r10)/s; qz = (r02+r20)/s;
        } else if (r11 > r22) {
            float s = (float) Math.sqrt(1.0 + r11 - r00 - r22) * 2f;
            qw = (r02-r20)/s; qx = (r01+r10)/s; qy = 0.25f * s; qz = (r12+r21)/s;
        } else {
            float s = (float) Math.sqrt(1.0 + r22 - r00 - r11) * 2f;
            qw = (r10-r01)/s; qx = (r02+r20)/s; qy = (r12+r21)/s; qz = 0.25f * s;
        }
        return new float[]{qx, qy, qz, qw};
    }

    /** Create quaternion from axis-angle. Axis [ax,ay,az], angle in radians. */
    private static float[] quatFromAA(float ax, float ay, float az, float angle) {
        float halfAngle = angle * 0.5f;
        float s = (float) Math.sin(halfAngle);
        return new float[]{ax*s, ay*s, az*s, (float) Math.cos(halfAngle)};
    }

    /** Rotate vector [vx,vy,vz] by quaternion [x,y,z,w]. */
    private static float[] quatRotateVec(float[] q, float vx, float vy, float vz) {
        float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        // t = 2 * cross(q.xyz, v)
        float tx = 2f * (qy*vz - qz*vy);
        float ty = 2f * (qz*vx - qx*vz);
        float tz = 2f * (qx*vy - qy*vx);
        return new float[]{
            vx + qw*tx + (qy*tz - qz*ty),
            vy + qw*ty + (qz*tx - qx*tz),
            vz + qw*tz + (qx*ty - qy*tx)
        };
    }

    private static float randomInRange(float min, float max) {
        return min + (float) Math.random() * (max - min);
    }

    private void simulateParticles2(float dt) {
        if (particle2States == null || dt <= 0f) return;

        Map<Integer, float[]> wm = lastWorldMap;
        if (wm == null) return;

        SequenceInfo seq = (currentSeqIdx >= 0 && currentSeqIdx < animData.sequences().size())
                ? animData.sequences().get(currentSeqIdx) : null;
        long[] globalSeqs = animData.globalSequences();

        for (Particle2State ps : particle2States) {
            ParticleEmitter2Data pd = ps.data;
            float[] matrix = wm.get(pd.objectId());
            if (matrix == null) continue;

            float gravity = interpolateScalar(pd.gravityTrack(), seq, globalSeqs, pd.gravity());
            float lifeSpan = pd.lifeSpan();
            if (lifeSpan <= 0f) lifeSpan = 1f;

            // Extract world scale for gravity scaling
            float[] worldScale = extractWorldScale(matrix);

            int cap = ps.capacity();

            // Update existing particles: age and physics
            for (int pi = 0; pi < ps.count; pi++) {
                int idx = (ps.tail() + pi) % cap;
                ps.age[idx] -= dt; // health counts down
                if (ps.age[idx] <= 0f) continue;
                // Gravity scaled by world Z scale (matching Reteras)
                ps.vel[idx][2] -= gravity * worldScale[2] * dt;
                ps.pos[idx][0] += ps.vel[idx][0] * dt;
                ps.pos[idx][1] += ps.vel[idx][1] * dt;
                ps.pos[idx][2] += ps.vel[idx][2] * dt;
            }

            // Remove dead particles from tail
            while (ps.count > 0) {
                int tailIdx = ps.tail();
                if (ps.age[tailIdx] > 0f) break;
                ps.count--;
            }

            // Visibility and user toggle gate emission
            float vis = isEmitterEnabled(pd.objectId())
                    ? interpolateScalar(pd.visibilityTrack(), seq, globalSeqs, 1f) : 0f;
            if (vis <= 0f) { ps.emission = 0f; continue; }

            // Animated parameters
            float emissionRate = interpolateScalar(pd.emissionRateTrack(), seq, globalSeqs, pd.emissionRate());
            float speed = interpolateScalar(pd.speedTrack(), seq, globalSeqs, pd.speed());
            float variation = interpolateScalar(pd.variationTrack(), seq, globalSeqs, pd.variation());
            float latitude = interpolateScalar(pd.latitudeTrack(), seq, globalSeqs, pd.latitude());
            float latRad = (float) Math.toRadians(latitude);
            float width = interpolateScalar(pd.widthTrack(), seq, globalSeqs, pd.width()) * 0.5f;
            float length = interpolateScalar(pd.lengthTrack(), seq, globalSeqs, pd.length()) * 0.5f;

            // Squirt mode: emit burst only when emission rate changes
            if (pd.squirt() != 0) {
                if (emissionRate != ps.lastEmissionRate) {
                    ps.emission += emissionRate;
                }
                ps.lastEmissionRate = emissionRate;
            } else {
                ps.emission += emissionRate * dt;
            }

            // Extract world rotation for velocity direction
            float[] worldRot = pd.isModelSpace() ? new float[]{0,0,0,1} : extractWorldRotation(matrix);

            while (ps.emission >= 1f) {
                ps.emission -= 1f;

                // Emit head and/or tail particles
                boolean emitHead = pd.headOrTail() == 0 || pd.headOrTail() == 2;
                boolean emitTail = pd.headOrTail() == 1 || pd.headOrTail() == 2;

                // Compute shared spawn data
                // Local position: pivot + random offset in width/length area
                float lx = ps.pivot[0] + randomInRange(-width, width);
                float ly = ps.pivot[1] + randomInRange(-length, length);
                float lz = ps.pivot[2];

                // World position
                float spawnX, spawnY, spawnZ;
                if (pd.isModelSpace()) {
                    spawnX = lx; spawnY = ly; spawnZ = lz;
                } else {
                    float[] wp = transformPoint(matrix, lx, ly, lz);
                    spawnX = wp[0]; spawnY = wp[1]; spawnZ = wp[2];
                }

                // Velocity direction using Reteras' quaternion rotation approach:
                // Start with [0,0,1], rotate by: rotZ(π/2) * rotX(random(-lat,lat))
                // If not line emitter: also rotY(random(-lat,lat))
                float[] rotZ = quatFromAA(0, 0, 1, (float)(Math.PI / 2.0));
                float[] rotX = quatFromAA(1, 0, 0, randomInRange(-latRad, latRad));
                float[] combined = quatMul(rotX, rotZ);
                if (!pd.isLineEmitter()) {
                    float[] rotY = quatFromAA(0, 1, 0, randomInRange(-latRad, latRad));
                    combined = quatMul(rotY, combined);
                }
                // Apply world rotation (if not model space)
                if (!pd.isModelSpace()) {
                    combined = quatMul(worldRot, combined);
                }
                float[] velDir = quatRotateVec(combined, 0, 0, 1);

                // Apply speed with variation (random range, not multiplier)
                float s = speed + randomInRange(-variation, variation);

                // Scale velocity by world scale
                float vx = velDir[0] * s * worldScale[0];
                float vy = velDir[1] * s * worldScale[1];
                float vz = velDir[2] * s * worldScale[2];

                if (emitHead) emitParticle2(ps, spawnX, spawnY, spawnZ, vx, vy, vz, lifeSpan, true, worldScale);
                if (emitTail) emitParticle2(ps, spawnX, spawnY, spawnZ, vx, vy, vz, lifeSpan, false, worldScale);
            }
        }
    }

    private static void emitParticle2(Particle2State ps, float px, float py, float pz,
                                       float vx, float vy, float vz,
                                       float lifeSpan, boolean head, float[] worldScale) {
        int cap = ps.capacity();
        int slot = ps.head;
        ps.pos[slot][0] = px; ps.pos[slot][1] = py; ps.pos[slot][2] = pz;
        ps.vel[slot][0] = vx; ps.vel[slot][1] = vy; ps.vel[slot][2] = vz;
        ps.age[slot] = lifeSpan; // health counts down from lifeSpan to 0
        ps.isHead[slot] = head;
        ps.nodeScale[slot][0] = worldScale[0];
        ps.nodeScale[slot][1] = worldScale[1];
        ps.nodeScale[slot][2] = worldScale[2];
        ps.head = (ps.head + 1) % cap;
        if (ps.count < cap) ps.count++;
    }

    /**
     * Compute camera billboard vectors in WC3 Z-up model space.
     * Returns 7 vectors matching Reteras' ViewerCamera.billboardedVectors:
     *   [0..3] = 4 corners of a 2x2 rectangle billboarded to camera
     *   [4] = camera right (X axis)
     *   [5] = camera up (Y axis)
     *   [6] = camera forward (Z axis)
     *
     * Uses computeCameraRotationQuat() which correctly produces the inverse
     * camera rotation in Z-up model space: Rx(90) * Ry(-yaw) * Rx(-pitch).
     * Each base vector (in camera space) is rotated by this quaternion to
     * produce the corresponding direction in model space — exactly how
     * Reteras' ViewerCamera billboards its vectors.
     */
    private float[][] computeBillboardVectors() {
        float[] invRot = computeCameraRotationQuat();
        // Base vectors in camera space (matching Reteras' ViewerCamera.vectors)
        // Corners of a 2x2 rectangle + 3 unit axes
        float[][] base = {
            {-1, -1, 0}, {-1, 1, 0}, {1, 1, 0}, {1, -1, 0},
            {1, 0, 0}, {0, 1, 0}, {0, 0, 1}
        };
        float[][] result = new float[7][];
        for (int i = 0; i < 7; i++) {
            result[i] = quatRotateVec(invRot, base[i][0], base[i][1], base[i][2]);
        }
        return result;
    }

    /**
     * Build particle geometry for all PE2 emitters into the given buffer.
     * Uses billboarded vectors for head quads and velocity-oriented geometry for tails,
     * matching Reteras' Model Studio approach.
     */
    private int buildParticle2Geometry(float[] buf, float[][] bbVectors) {
        if (particle2States == null) return 0;
        int vi = 0;
        int maxFloats = P2_MAX_VERTS * P2_FLOATS_PER_VERT;

        // XY quad (non-billboarded) corners
        float[][] xyQuadVecs = {
            {-1, 1, 0}, {1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 0}, {0, 1, 0}, {0, 0, 1}
        };

        Map<Integer, float[]> wm = lastWorldMap;

        for (Particle2State ps : particle2States) {
            if (ps.count == 0) continue;
            ParticleEmitter2Data pd = ps.data;
            float lifeSpan = pd.lifeSpan();
            if (lifeSpan <= 0f) lifeSpan = 1f;
            float timeMid = pd.timeMiddle();

            int rows = Math.max(1, pd.rows());
            int cols = Math.max(1, pd.columns());

            float[][] segColors = pd.segmentColors();
            short[] segAlphas = pd.segmentAlphas();
            float[] segScaling = pd.segmentScaling();

            long[][] headIntervals = pd.headIntervals();
            long[][] tailIntervals = pd.tailIntervals();

            // Choose billboard or XY quad vectors
            float[][] vectors = pd.isXYQuad() ? xyQuadVecs : bbVectors;

            // Get world matrix for model-space transform
            float[] worldMatrix = (pd.isModelSpace() && wm != null) ? wm.get(pd.objectId()) : null;

            int cap = ps.capacity();
            for (int pi = 0; pi < ps.count; pi++) {
                int idx = (ps.tail() + pi) % cap;
                float health = ps.age[idx];
                if (health <= 0f) continue;
                float lifeFactor = (lifeSpan - health) / lifeSpan; // 0 at birth, 1 at death

                // 3-segment lifecycle interpolation (matching Reteras)
                float factor;
                int firstColor;
                long[][] interval;

                if (lifeFactor < timeMid) {
                    factor = timeMid > 0f ? lifeFactor / timeMid : 0f;
                    firstColor = 0;
                    interval = ps.isHead[idx] ? headIntervals : tailIntervals;
                } else {
                    factor = timeMid < 1f ? (lifeFactor - timeMid) / (1f - timeMid) : 1f;
                    firstColor = 1;
                    // For decay phase, use decay intervals (index 1 in the interval arrays)
                    interval = ps.isHead[idx] ? headIntervals : tailIntervals;
                }
                factor = Math.min(factor, 1f);

                // Color interpolation
                float cr = lerp(segColors[firstColor][0], segColors[firstColor + 1][0], factor);
                float cg = lerp(segColors[firstColor][1], segColors[firstColor + 1][1], factor);
                float cb = lerp(segColors[firstColor][2], segColors[firstColor + 1][2], factor);
                float ca = lerp((segAlphas[firstColor] & 0xFF) / 255f,
                                (segAlphas[firstColor + 1] & 0xFF) / 255f, factor);

                // Scale interpolation
                float scale = lerp(segScaling[firstColor], segScaling[firstColor + 1], factor);

                // UV computation matching Reteras:
                // index = start + floor(spriteCount * repeat * factor) % spriteCount
                float left, top, right, bottom;
                // Select the correct interval row based on phase
                long[] intervalRow;
                if (lifeFactor < timeMid) {
                    // Life span phase - use interval[0]
                    intervalRow = (interval != null && interval.length > 0) ? interval[0] : new long[]{0, 0, 1};
                } else {
                    // Decay phase - use interval[1]
                    intervalRow = (interval != null && interval.length > 1) ? interval[1] : new long[]{0, 0, 1};
                }
                float uvStart = intervalRow.length > 0 ? intervalRow[0] : 0;
                float uvEnd = intervalRow.length > 1 ? intervalRow[1] : 0;
                float uvRepeat = intervalRow.length > 2 ? intervalRow[2] : 1;
                float spriteCount = uvEnd - uvStart;
                float index = 0;
                if (spriteCount > 0 && (cols > 1 || rows > 1)) {
                    index = (float)(uvStart + (Math.floor(spriteCount * uvRepeat * factor) % spriteCount));
                }
                left = index % cols;
                top = (int)(index / cols);
                right = left + 1;
                bottom = top + 1;
                // Normalize UV to [0,1]
                float u0 = left / cols, v0 = top / rows;
                float u1 = right / cols, v1 = bottom / rows;

                // Node scale at spawn time
                float nsx = ps.nodeScale[idx][0];
                float nsy = ps.nodeScale[idx][1];
                float nsz = ps.nodeScale[idx][2];
                float scalex = scale * nsx;
                float scaley = scale * nsy;
                float scalez = scale * nsz;

                if (ps.isHead[idx]) {
                    // Head: billboard quad
                    if (vi + 6 * P2_FLOATS_PER_VERT > maxFloats) break;

                    float px = ps.pos[idx][0], py = ps.pos[idx][1], pz = ps.pos[idx][2];
                    // Model space: transform to world at render time
                    if (pd.isModelSpace() && worldMatrix != null) {
                        float[] wp = transformPoint(worldMatrix, px, py, pz);
                        px = wp[0]; py = wp[1]; pz = wp[2];
                    }

                    // 4 corners using billboard vectors scaled per-axis
                    float[] pv0 = vectors[0], pv1 = vectors[1], pv2 = vectors[2], pv3 = vectors[3];
                    float v0x = px + pv0[0]*scalex, v0y = py + pv0[1]*scaley, v0z = pz + pv0[2]*scalez;
                    float v1x = px + pv1[0]*scalex, v1y = py + pv1[1]*scaley, v1z = pz + pv1[2]*scalez;
                    float v2x = px + pv2[0]*scalex, v2y = py + pv2[1]*scaley, v2z = pz + pv2[2]*scalez;
                    float v3x = px + pv3[0]*scalex, v3y = py + pv3[1]*scaley, v3z = pz + pv3[2]*scalez;

                    // Two triangles: (0,1,2) and (0,2,3) — matching Reteras vertex winding
                    vi = addP2Vert(buf, vi, v0x, v0y, v0z, u1, v1, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, v1x, v1y, v1z, u0, v1, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, v2x, v2y, v2z, u0, v0, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, v0x, v0y, v0z, u1, v1, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, v2x, v2y, v2z, u0, v0, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, v3x, v3y, v3z, u1, v0, cr, cg, cb, ca);
                } else {
                    // Tail: velocity-oriented quad
                    if (vi + 6 * P2_FLOATS_PER_VERT > maxFloats) break;

                    float tailLength = pd.tailLength();
                    float vx = ps.vel[idx][0], vy = ps.vel[idx][1], vz = ps.vel[idx][2];

                    // Start = position - velocity * tailLength, End = position
                    float startX = ps.pos[idx][0] - tailLength * vx;
                    float startY = ps.pos[idx][1] - tailLength * vy;
                    float startZ = ps.pos[idx][2] - tailLength * vz;
                    float endX = ps.pos[idx][0];
                    float endY = ps.pos[idx][1];
                    float endZ = ps.pos[idx][2];

                    // Model space: transform start and end to world
                    if (pd.isModelSpace() && worldMatrix != null) {
                        float[] ws = transformPoint(worldMatrix, startX, startY, startZ);
                        startX = ws[0]; startY = ws[1]; startZ = ws[2];
                        float[] we = transformPoint(worldMatrix, endX, endY, endZ);
                        endX = we[0]; endY = we[1]; endZ = we[2];
                    }

                    // Tail direction
                    float tdx = endX - startX, tdy = endY - startY, tdz = endZ - startZ;
                    float tlen = (float) Math.sqrt(tdx*tdx + tdy*tdy + tdz*tdz);
                    if (tlen > 0.0001f) { tdx /= tlen; tdy /= tlen; tdz /= tlen; }

                    // Normal: cross(cameraForward, tailDir) — matching Reteras
                    float[] camFwd = vectors[6]; // camera forward (Z axis)
                    float nx = camFwd[1]*tdz - camFwd[2]*tdy;
                    float ny = camFwd[2]*tdx - camFwd[0]*tdz;
                    float nz = camFwd[0]*tdy - camFwd[1]*tdx;
                    float nlen = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
                    if (nlen > 0.0001f) { nx /= nlen; ny /= nlen; nz /= nlen; }

                    // Scale the normal
                    float normalX = nx * scalex, normalY = ny * scaley, normalZ = nz * scalez;

                    // 4 corners matching Reteras' winding order
                    float c0x = startX - normalX, c0y = startY - normalY, c0z = startZ - normalZ;
                    float c1x = endX - normalX,   c1y = endY - normalY,   c1z = endZ - normalZ;
                    float c2x = endX + normalX,   c2y = endY + normalY,   c2z = endZ + normalZ;
                    float c3x = startX + normalX, c3y = startY + normalY, c3z = startZ + normalZ;

                    // Two triangles: (0,1,2) and (0,2,3)
                    vi = addP2Vert(buf, vi, c0x, c0y, c0z, u1, v1, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, c1x, c1y, c1z, u0, v1, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, c2x, c2y, c2z, u0, v0, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, c0x, c0y, c0z, u1, v1, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, c2x, c2y, c2z, u0, v0, cr, cg, cb, ca);
                    vi = addP2Vert(buf, vi, c3x, c3y, c3z, u1, v0, cr, cg, cb, ca);
                }
            }
        }
        return vi / P2_FLOATS_PER_VERT;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static int addP2Vert(float[] buf, int off,
                                  float x, float y, float z,
                                  float u, float v,
                                  float r, float g, float b, float a) {
        buf[off++] = x; buf[off++] = y; buf[off++] = z;
        buf[off++] = u; buf[off++] = v;
        buf[off++] = r; buf[off++] = g; buf[off++] = b; buf[off++] = a;
        return off;
    }

    private void drawParticles2(float[] mvp) {
        if (particle2States == null || particle2Shader == 0) return;

        // Compute billboard vectors in WC3 Z-up model space (7 vectors like Reteras)
        float[][] bbVectors = computeBillboardVectors();

        // Build geometry
        float[] buf = new float[P2_MAX_VERTS * P2_FLOATS_PER_VERT];
        int vertexCount = buildParticle2Geometry(buf, bbVectors);
        if (vertexCount == 0) return;

        // Upload to VBO
        glBindBuffer(GL_ARRAY_BUFFER, particle2Vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, java.util.Arrays.copyOf(buf, vertexCount * P2_FLOATS_PER_VERT));

        glUseProgram(particle2Shader);
        glUniformMatrix4fv(particle2Mvp, false, mvp);
        if (particle2Sampler >= 0) glUniform1i(particle2Sampler, 0);

        glEnable(GL_BLEND);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);

        // Draw each emitter's portion of the buffer
        int offset = 0;
        for (int ei = 0; ei < particle2States.length; ei++) {
            Particle2State ps = particle2States[ei];
            if (ps.count == 0) continue;

            // Count actual vertices emitted for this emitter
            int emitterVerts = 0;
            int cap = ps.capacity();
            for (int pi = 0; pi < ps.count; pi++) {
                int idx = (ps.tail() + pi) % cap;
                if (ps.age[idx] > 0f) emitterVerts += 6; // 6 verts per quad
            }
            if (offset + emitterVerts > vertexCount) emitterVerts = vertexCount - offset;
            if (emitterVerts <= 0) continue;

            // Bind texture
            boolean hasTex = (particle2Textures != null && particle2Textures[ei] != 0);
            if (particle2HasTex >= 0) glUniform1i(particle2HasTex, hasTex ? 1 : 0);
            if (hasTex) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, particle2Textures[ei]);
            }

            // Set blend mode matching Reteras' getBlendSrc/getBlendDst
            applyParticle2BlendMode(ps.data.filterMode());

            glBindVertexArray(particle2Vao);
            glDrawArrays(GL_TRIANGLES, offset, emitterVerts);
            offset += emitterVerts;
        }

        glBindVertexArray(0);
        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
    }

    private static void applyParticle2BlendMode(int filterMode) {
        switch (filterMode) {
            case 0 -> glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // Blend
            case 1 -> glBlendFunc(GL_SRC_ALPHA, GL_ONE);                 // Additive
            case 2 -> glBlendFunc(GL_ZERO, GL_SRC_COLOR);                // Modulate
            case 3 -> glBlendFunc(GL_DST_COLOR, GL_SRC_COLOR);           // Modulate 2x
            case 4 -> glBlendFunc(GL_SRC_ALPHA, GL_ONE);                 // AlphaKey (additive)
            default -> glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    private void resetParticles2() {
        if (particle2States == null) return;
        for (Particle2State ps : particle2States) ps.reset();
    }
}
