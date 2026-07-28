package com.hiveworkshop.ui;

import com.hiveworkshop.model.*;
import com.hiveworkshop.parser.BoneAnimator;
import com.hiveworkshop.parser.GameDataSource;
import com.hiveworkshop.parser.ReterasModelParser;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.opengl.awt.GLData;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Offscreen thumbnail renderer for the asset browser grid.
 *
 * <p>Uses a hidden AWTGLCanvas to obtain a GL context. All GL work happens
 * inside {@code paintGL()}, triggered by calling {@code canvas.render()} from
 * a dedicated processing thread. Each model is rendered to an FBO at 256x256,
 * read back via {@code glReadPixels}, and cached as a {@link BufferedImage}.
 */
public final class ThumbnailRenderer {

    private int thumbSize = 256;
    private int renderSize = 512; // supersample then downsample
    private boolean fboNeedsResize;

    private static final int[][] DEFAULT_TEAM_COLORS = {
        {255,   3,   3}, {  0,  66, 255}, {  0, 206, 209}, { 84,   0, 129},
        {255, 252,   0}, {254, 138,  14}, { 32, 192,   0}, {229,  91, 176},
        {149, 150, 151}, {126, 191, 241}, {  0,  97,  31}, { 78,  42,   4},
    };

    // Camera defaults
    private float cameraYaw = 200f;
    private float cameraPitch = 20f;
    private String animationName = "Stand";
    private volatile int teamColorIdx = 0;

    private final JFrame hiddenFrame;
    private final AWTGLCanvas canvas;
    private final LinkedBlockingQueue<ThumbnailRequest> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger generation = new AtomicInteger(0);
    private final ConcurrentHashMap<Path, BufferedImage> cache = new ConcurrentHashMap<>();

    private volatile boolean running = true;

    // Disk cache directory (~/.wc3-model-explorer/thumbcache/)
    private static final Path DISK_CACHE_DIR = resolveDiskCacheDir();

    // Current request being processed (set by GL thread, read by paintGL)
    private volatile ThumbnailRequest currentRequest;
    private volatile BufferedImage currentResult;

    // GL resources (initialized once in paintGL on first call)
    private int fbo, fboColorTex, fboDepthRbo;
    private int texShader, texMvpLoc, texMvLoc, texSamplerLoc, texHasTexLoc, texAlphaThreshLoc, texAlphaLoc, texUVTransformLoc, texGeosetColorLoc, texUnshadedLoc;
    private boolean glInitialized;

    // Background color
    private float bgR = 15f / 255f, bgG = 20f / 255f, bgB = 25f / 255f;

    public ThumbnailRenderer() {
        GLData d = new GLData();
        d.doubleBuffer = true;
        d.depthSize = 24;

        canvas = new AWTGLCanvas(d) {
            @Override
            public void initGL() {
                GL.createCapabilities();
            }

            @Override
            public void paintGL() {
                if (!glInitialized) {
                    initGlResources();
                    glInitialized = true;
                }
                if (fboNeedsResize) {
                    resizeFbo();
                    fboNeedsResize = false;
                }
                ThumbnailRequest req = currentRequest;
                if (req != null && req != ThumbnailRequest.POISON) {
                    currentResult = renderThumbnail(req);
                }
            }
        };
        canvas.setSize(1, 1);

        hiddenFrame = new JFrame();
        hiddenFrame.setType(java.awt.Window.Type.UTILITY);
        hiddenFrame.setUndecorated(true);
        hiddenFrame.setSize(1, 1);
        hiddenFrame.setLocation(-100, -100);
        hiddenFrame.add(canvas);
        hiddenFrame.setVisible(true);

        // Start the GL processing thread
        Thread glThread = new Thread(this::processQueue, "Thumbnail-GL");
        glThread.setDaemon(true);
        glThread.start();
    }

    /** Queue a thumbnail request. Callback is invoked on the EDT. */
    public void request(ModelAsset asset, Path rootDir, Consumer<BufferedImage> callback) {
        int gen = generation.get();
        queue.offer(new ThumbnailRequest(asset, rootDir, gen, callback));
    }

    /** Get a cached thumbnail, or null if not yet rendered. */
    public BufferedImage getThumbnail(Path modelPath) {
        return cache.get(modelPath);
    }

    /** Cancel all pending requests (bumps generation so queued items are skipped). */
    public void cancelPending() {
        generation.incrementAndGet();
        queue.clear();
    }

    /** Clear the in-memory and disk thumbnail cache (e.g. on rescan). */
    public void clearCache() {
        cache.clear();
        clearDiskCache();
    }

    public void setCameraAngles(float yaw, float pitch) {
        this.cameraYaw = yaw;
        this.cameraPitch = pitch;
    }

    public void setAnimationName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!trimmed.equals(animationName)) {
            animationName = trimmed;
            cancelPending();
            clearCache(); // pose changed → invalidate memory + disk cache so thumbnails regenerate
        }
    }

    public void setTeamColor(int idx) {
        int clamped = TeamColorOptions.clampIndex(idx);
        if (clamped != teamColorIdx) {
            teamColorIdx = clamped;
            cancelPending();
            clearCache();
        }
    }

    public void setQuality(int newRenderSize, int newThumbSize) {
        if (newRenderSize != this.renderSize || newThumbSize != this.thumbSize) {
            this.renderSize = newRenderSize;
            this.thumbSize = newThumbSize;
            this.fboNeedsResize = true;
        }
    }

    public void setBackgroundColor(int r, int g, int b) {
        bgR = r / 255f;
        bgG = g / 255f;
        bgB = b / 255f;
    }

    public void shutdown() {
        running = false;
        queue.offer(ThumbnailRequest.POISON);
        SwingUtilities.invokeLater(() -> {
            hiddenFrame.setVisible(false);
            hiddenFrame.dispose();
        });
    }

    /** Clear disk thumbnail cache. */
    public void clearDiskCache() {
        if (DISK_CACHE_DIR == null) return;
        try (var files = Files.list(DISK_CACHE_DIR)) {
            files.filter(p -> p.toString().endsWith(".png")).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // ── GL thread ────────────────────────────────────────────────────────────

    private void processQueue() {
        // Wait for the canvas to be displayable
        try { Thread.sleep(300); } catch (InterruptedException e) { return; }

        while (running) {
            ThumbnailRequest req;
            try {
                req = queue.take();
            } catch (InterruptedException e) {
                break;
            }
            if (req == ThumbnailRequest.POISON) break;
            if (req.generation() != generation.get()) continue; // stale

            // Try loading from disk cache first
            BufferedImage diskCached = loadFromDiskCache(req.asset().path());
            if (diskCached != null && req.generation() == generation.get()) {
                cache.put(req.asset().path(), diskCached);
                SwingUtilities.invokeLater(() -> req.callback().accept(diskCached));
                continue;
            }

            try {
                // Set the request and trigger paintGL via canvas.render()
                currentRequest = req;
                currentResult = null;
                canvas.render();

                BufferedImage thumb = currentResult;
                currentRequest = null;
                currentResult = null;

                if (thumb != null && req.generation() == generation.get()) {
                    cache.put(req.asset().path(), thumb);
                    saveToDiskCache(req.asset().path(), thumb);
                    SwingUtilities.invokeLater(() -> req.callback().accept(thumb));
                }
            } catch (Exception ex) {
                System.err.println("[Thumbnail] Error rendering " + req.asset().fileName() + ": " + ex);
                currentRequest = null;
                currentResult = null;
                // Cache a placeholder so the card stops showing "Loading..."
                BufferedImage errImg = createPlaceholder("Render error");
                if (req.generation() == generation.get()) {
                    cache.put(req.asset().path(), errImg);
                    SwingUtilities.invokeLater(() -> req.callback().accept(errImg));
                }
            }
        }
    }

    // ── GL methods (called from paintGL, context is current) ─────────────────

    private void initGlResources() {
        texShader = GlPreviewCanvas.linkProgram(GlPreviewCanvas.TEX_VERT, GlPreviewCanvas.TEX_FRAG);
        if (texShader != 0) {
            texMvpLoc = glGetUniformLocation(texShader, "mvp");
            texMvLoc = glGetUniformLocation(texShader, "uModelView");
            texSamplerLoc = glGetUniformLocation(texShader, "uTex");
            texHasTexLoc = glGetUniformLocation(texShader, "uHasTex");
            texAlphaThreshLoc = glGetUniformLocation(texShader, "uAlphaThreshold");
            texAlphaLoc = glGetUniformLocation(texShader, "uAlpha");
            texUVTransformLoc = glGetUniformLocation(texShader, "uUVTransform");
            texGeosetColorLoc = glGetUniformLocation(texShader, "uGeosetColor");
            texUnshadedLoc = glGetUniformLocation(texShader, "uUnshaded");
        }

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        fboColorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fboColorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, renderSize, renderSize, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboColorTex, 0);

        fboDepthRbo = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, fboDepthRbo);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, renderSize, renderSize);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, fboDepthRbo);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[Thumbnail] FBO incomplete: 0x" + Integer.toHexString(status));
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        System.out.println("[Thumbnail] GL initialized, FBO " + renderSize + "x" + renderSize + " -> " + thumbSize + "x" + thumbSize);
    }

    private void resizeFbo() {
        glBindTexture(GL_TEXTURE_2D, fboColorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, renderSize, renderSize, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);

        glBindRenderbuffer(GL_RENDERBUFFER, fboDepthRbo);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, renderSize, renderSize);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);

        System.out.println("[Thumbnail] FBO resized to " + renderSize + "x" + renderSize + " -> " + thumbSize + "x" + thumbSize);
    }

    private BufferedImage renderThumbnail(ThumbnailRequest req) {
        ReterasParsedModel parsed;
        try {
            parsed = ReterasModelParser.parse(req.asset().path());
        } catch (Exception ex) {
            return createPlaceholder("Parse error: " + ex.getMessage());
        }
        if (parsed == null) return createPlaceholder("Failed to parse");

        ModelMesh mesh = parsed.mesh();
        GeosetTexData[] texData = parsed.texData();
        if (mesh == null || mesh.isEmpty() || texData == null || texData.length == 0) {
            if (parsed.metadata() == ModelMetadata.EMPTY) {
                return createPlaceholder("Failed to parse");
            }
            // Model has metadata but no geometry — still valid if it has nodes/particles
            boolean hasContent = parsed.animData().hasAnimation()
                    || (parsed.particleEmitters2() != null && parsed.particleEmitters2().length > 0)
                    || (parsed.ribbonEmitters() != null && parsed.ribbonEmitters().length > 0);
            return createPlaceholder(hasContent ? "No geometry\n(has nodes/effects)" : "No geometry");
        }

        Path modelDir = req.asset().path().getParent();
        Path rootDir = req.rootDir();
        int currentTeamColor = teamColorIdx;

        // Build per-geoset VAOs and load textures
        int geoCount = texData.length;
        int[] geoVao = new int[geoCount];
        int[] geoVbo = new int[geoCount];
        int[] geoNormVbo = new int[geoCount];
        int[] geoUvVbo = new int[geoCount];
        int[] geoEbo = new int[geoCount];
        int[] geoIndexCount = new int[geoCount];
        int[][] geoTex = new int[geoCount][];

        ModelAnimData animData = parsed.animData() != null ? parsed.animData() : ModelAnimData.EMPTY;
        int[] allIndices = mesh.indices();
        int vertOffset = 0, indexOffset = 0, gi = 0;

        // Find matching animation sequence for pose and visibility
        SequenceInfo thumbSeq = null;
        Map<Integer, float[]> boneMatrices = null;
        // "None" (or an unmatched name) leaves thumbSeq null → model rendered in its default pose.
        if (!animationName.isEmpty() && !animationName.equalsIgnoreCase("None") && animData.hasAnimation()) {
            thumbSeq = findSequence(animData.sequences(), animationName);
            if (thumbSeq != null) {
                boneMatrices = BoneAnimator.computeWorldMatrices(
                        animData.bones(), thumbSeq.start(), thumbSeq.start(), thumbSeq.end(), animData.globalSequences());
            }
        }

        long[] globalSeqs = animData.globalSequences();

        // Sample per-geoset alpha (KGAO visibility) at the first frame
        float[] geoAlpha = new float[geoCount];
        java.util.Arrays.fill(geoAlpha, 1.0f);
        {
            long t = thumbSeq != null ? thumbSeq.start() : 0;
            long s0 = thumbSeq != null ? thumbSeq.start() : 0;
            long s1 = thumbSeq != null ? thumbSeq.end() : 0;
            for (int i = 0; i < geoCount; i++) {
                AnimTrack track = animData.geosetAlpha().get(i);
                if (track != null && !track.isEmpty()) {
                    float val = BoneAnimator.interpTrackScalar(track, t, s0, s1, globalSeqs, 1f);
                    geoAlpha[i] = Math.max(0f, Math.min(1f, val));
                }
            }
        }

        // Sample per-layer alpha (KMTA) at the first frame
        float[][] layerAlpha = new float[geoCount][];
        if (!animData.layerAlpha().isEmpty()) {
            long t = thumbSeq != null ? thumbSeq.start() : 0;
            long s0 = thumbSeq != null ? thumbSeq.start() : 0;
            long s1 = thumbSeq != null ? thumbSeq.end() : 0;
            for (int i = 0; i < geoCount; i++) {
                if (i >= texData.length) continue;
                var layers = texData[i].layers();
                layerAlpha[i] = new float[layers.size()];
                java.util.Arrays.fill(layerAlpha[i], 1.0f);
                for (int li = 0; li < layers.size(); li++) {
                    AnimTrack track = animData.layerAlpha().get(ModelAnimData.layerKey(i, li));
                    if (track != null && !track.isEmpty()) {
                        float val = BoneAnimator.interpTrackScalar(track, t, s0, s1, globalSeqs, layers.get(li).alpha());
                        layerAlpha[i][li] = Math.max(0f, Math.min(1f, val));
                    } else {
                        layerAlpha[i][li] = layers.get(li).alpha();
                    }
                }
            }
        }

        // Sample per-layer UV transforms at the first frame
        float[][][] layerUVTransforms = new float[geoCount][][];
        {
            Map<Long, TextureAnimTracks> taMap = animData.textureAnims();
            long t = thumbSeq != null ? thumbSeq.start() : 0;
            long s0 = thumbSeq != null ? thumbSeq.start() : 0;
            long s1 = thumbSeq != null ? thumbSeq.end() : 0;
            for (int i = 0; i < geoCount; i++) {
                if (i >= texData.length) continue;
                int lc = texData[i].layers().size();
                layerUVTransforms[i] = new float[Math.max(lc, 1)][];
                for (int li = 0; li < lc; li++) {
                    TextureAnimTracks ta = taMap.get(ModelAnimData.layerKey(i, li));
                    if (ta == null || !ta.hasAnimation()) continue;
                    float[] trans = BoneAnimator.interpTrackVec3(ta.translation(), t, s0, s1, globalSeqs, 0, 0, 0);
                    float[] rot = BoneAnimator.interpTrackQuat(ta.rotation(), t, s0, s1, globalSeqs);
                    float[] scl = BoneAnimator.interpTrackVec3(ta.scale(), t, s0, s1, globalSeqs, 1, 1, 1);
                    layerUVTransforms[i][li] = GlPreviewCanvas.buildUVTransformMatrix(trans, rot, scl);
                }
            }
        }

        // Sample per-geoset color (KGAC tint) at the first frame
        float[][] geoColor = new float[geoCount][];
        {
            long t = thumbSeq != null ? thumbSeq.start() : 0;
            long s0 = thumbSeq != null ? thumbSeq.start() : 0;
            long s1 = thumbSeq != null ? thumbSeq.end() : 0;
            for (int i = 0; i < geoCount; i++) {
                AnimTrack track = animData.geosetColor().get(i);
                if (track != null && !track.isEmpty()) {
                    geoColor[i] = BoneAnimator.interpTrackVec3(track, t, s0, s1, globalSeqs, 1f, 1f, 1f);
                } else {
                    float[] sc = animData.geosetStaticColor().get(i);
                    if (sc != null) geoColor[i] = sc;
                }
            }
        }

        // Track actual vertex bounds for camera framing
        float bMinX = Float.POSITIVE_INFINITY, bMinY = Float.POSITIVE_INFINITY, bMinZ = Float.POSITIVE_INFINITY;
        float bMaxX = Float.NEGATIVE_INFINITY, bMaxY = Float.NEGATIVE_INFINITY, bMaxZ = Float.NEGATIVE_INFINITY;

        for (GeosetSkinData skin : animData.geosets()) {
            int vc = skin.vertexCount();
            if (vc == 0) continue;
            if (gi >= geoCount) break;

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

                // Apply animation pose if bone matrices available
                if (boneMatrices != null && skin.hasSkinning()) {
                    float[] bindNormals = mesh.normals();
                    for (int vi = 0; vi < vc; vi++) {
                        float[] p = GlPreviewCanvas.transformVertex(skin, vi, boneMatrices);
                        verts[vi * 3] = p[0]; verts[vi * 3 + 1] = p[1]; verts[vi * 3 + 2] = p[2];
                        float[] n = GlPreviewCanvas.transformNormal(skin, vi, vertOffset, bindNormals, boneMatrices);
                        norms[vi * 3] = n[0]; norms[vi * 3 + 1] = n[1]; norms[vi * 3 + 2] = n[2];
                    }
                }

                // Accumulate bounds from visible geosets only
                if (geoAlpha[gi] > 0f) {
                    for (int vi = 0; vi < vc; vi++) {
                        float vx = verts[vi * 3], vy = verts[vi * 3 + 1], vz = verts[vi * 3 + 2];
                        bMinX = Math.min(bMinX, vx); bMaxX = Math.max(bMaxX, vx);
                        bMinY = Math.min(bMinY, vy); bMaxY = Math.max(bMaxY, vy);
                        bMinZ = Math.min(bMinZ, vz); bMaxZ = Math.max(bMaxZ, vz);
                    }
                }

                int[] indices = new int[faceCount];
                for (int ii = 0; ii < faceCount; ii++)
                    indices[ii] = allIndices[indexOffset + ii] - vertOffset;

                geoVao[gi] = glGenVertexArrays();
                geoVbo[gi] = glGenBuffers();
                geoNormVbo[gi] = glGenBuffers();
                geoEbo[gi] = glGenBuffers();
                geoIndexCount[gi] = faceCount;

                glBindVertexArray(geoVao[gi]);
                glBindBuffer(GL_ARRAY_BUFFER, geoVbo[gi]);
                glBufferData(GL_ARRAY_BUFFER, verts, GL_STATIC_DRAW);
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
                glEnableVertexAttribArray(0);

                // Per-vertex normals at location=2
                glBindBuffer(GL_ARRAY_BUFFER, geoNormVbo[gi]);
                glBufferData(GL_ARRAY_BUFFER, norms, GL_STATIC_DRAW);
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
                glBindVertexArray(0);

                // Load textures for all material layers
                var layers = texData[gi].layers();
                if (!layers.isEmpty()) {
                    geoTex[gi] = new int[layers.size()];
                    for (int li = 0; li < layers.size(); li++) {
                        var layer = layers.get(li);
                        geoTex[gi][li] = loadLayerTexture(layer.texturePath(), layer.replaceableId(), currentTeamColor, modelDir, rootDir);
                    }
                } else {
                    geoTex[gi] = new int[1];
                    geoTex[gi][0] = loadLayerTexture(texData[gi].texturePath(), texData[gi].replaceableId(), currentTeamColor, modelDir, rootDir);
                }

                indexOffset += faceCount;
            }
            vertOffset += vc;
            gi++;
        }

        // Render to FBO at supersampled resolution
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, renderSize, renderSize);
        glClearColor(bgR, bgG, bgB, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);

        // Compute bounds radius — prefer "Stand" sequence extents (Retera convention)
        float boundsRadius;
        if (thumbSeq != null && thumbSeq.boundsRadius() > 1f) {
            boundsRadius = thumbSeq.boundsRadius();
        } else if (thumbSeq != null && thumbSeq.hasExtent() && thumbSeq.extentRadius() > 0.001f) {
            boundsRadius = thumbSeq.extentRadius();
        } else if (bMinX <= bMaxX) {
            float dx = bMaxX - bMinX, dy = bMaxY - bMinY, dz = bMaxZ - bMinZ;
            boundsRadius = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5f;
        } else {
            boundsRadius = mesh.radius();
        }
        boundsRadius = Math.max(30f, Math.min(10000f, boundsRadius));
        // Camera target at actual AABB center of (possibly animated) vertices
        float cx = bMinX <= bMaxX ? (bMinX + bMaxX) * 0.5f : 0f;
        float cy = bMinX <= bMaxX ? (bMinY + bMaxY) * 0.5f : 0f;
        float cz = bMinX <= bMaxX ? (bMinZ + bMaxZ) * 0.5f : 0f;
        float[] mv = buildThumbnailModelView(cx, cy, cz, boundsRadius, cameraYaw, cameraPitch);
        float[] proj = GlPreviewCanvas.buildProjection(45f, 1f, 4f, 10000f);
        float[] mvp = GlPreviewCanvas.matMul(proj, mv);

        glUseProgram(texShader);
        glUniformMatrix4fv(texMvpLoc, false, mvp);
        if (texMvLoc >= 0) glUniformMatrix4fv(texMvLoc, false, mv);

        // Pass 1: opaque layers
        for (int i = 0; i < geoCount; i++) {
            if (geoVao[i] == 0 || geoIndexCount[i] == 0) continue;
            if (geoAlpha[i] <= 0f) continue;
            setGeosetColor(geoColor, i);
            drawGeosetLayers(i, geoVao, geoIndexCount, geoTex, texData, geoAlpha[i], layerAlpha, layerUVTransforms, true);
        }

        // Pass 2: transparent layers
        glEnable(GL_BLEND);
        glDepthMask(false);
        for (int i = 0; i < geoCount; i++) {
            if (geoVao[i] == 0 || geoIndexCount[i] == 0) continue;
            if (geoAlpha[i] <= 0f) continue;
            setGeosetColor(geoColor, i);
            drawGeosetLayers(i, geoVao, geoIndexCount, geoTex, texData, geoAlpha[i], layerAlpha, layerUVTransforms, false);
        }
        glDepthMask(true);
        glDisable(GL_BLEND);

        glUseProgram(0);
        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);

        // Read pixels at supersampled resolution
        ByteBuffer pixels = ByteBuffer.allocateDirect(renderSize * renderSize * 4)
                .order(ByteOrder.nativeOrder());
        glReadPixels(0, 0, renderSize, renderSize, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        // Convert to BufferedImage at render size (flip Y)
        BufferedImage hiRes = new BufferedImage(renderSize, renderSize, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < renderSize; y++) {
            for (int x = 0; x < renderSize; x++) {
                int srcIdx = ((renderSize - 1 - y) * renderSize + x) * 4;
                int r = pixels.get(srcIdx) & 0xFF;
                int g = pixels.get(srcIdx + 1) & 0xFF;
                int b = pixels.get(srcIdx + 2) & 0xFF;
                int a = pixels.get(srcIdx + 3) & 0xFF;
                hiRes.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }

        // Downsample to thumbnail size with bilinear filtering
        BufferedImage img = new BufferedImage(thumbSize, thumbSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(hiRes, 0, 0, thumbSize, thumbSize, null);
        g2.dispose();

        // Cleanup per-model GL resources
        for (int i = 0; i < geoCount; i++) {
            if (geoVao[i] != 0) glDeleteVertexArrays(geoVao[i]);
            if (geoVbo[i] != 0) glDeleteBuffers(geoVbo[i]);
            if (geoNormVbo[i] != 0) glDeleteBuffers(geoNormVbo[i]);
            if (geoUvVbo[i] != 0) glDeleteBuffers(geoUvVbo[i]);
            if (geoEbo[i] != 0) glDeleteBuffers(geoEbo[i]);
            if (geoTex[i] != null) {
                for (int t : geoTex[i]) { if (t != 0) glDeleteTextures(t); }
            }
        }

        return img;
    }

    private static final float[] IDENTITY_4X4 = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    private void setUVTransform(float[][][] layerUVXforms, int gi, int li) {
        if (texUVTransformLoc < 0) return;
        float[] m = null;
        if (layerUVXforms != null && gi < layerUVXforms.length
                && layerUVXforms[gi] != null && li < layerUVXforms[gi].length) {
            m = layerUVXforms[gi][li];
        }
        glUniformMatrix4fv(texUVTransformLoc, false, m != null ? m : IDENTITY_4X4);
    }

    private void setGeosetColor(float[][] geoColor, int gi) {
        if (texGeosetColorLoc < 0) return;
        float[] c = (gi < geoColor.length) ? geoColor[gi] : null;
        if (c != null) {
            glUniform3f(texGeosetColorLoc, c[0], c[1], c[2]);
        } else {
            glUniform3f(texGeosetColorLoc, 1f, 1f, 1f);
        }
    }

    private void drawGeosetLayers(int gi, int[] geoVao, int[] geoIndexCount, int[][] geoTex,
                                    GeosetTexData[] texData, float geoAlpha, float[][] layerAlphaArr,
                                    float[][][] layerUVXforms, boolean opaquePass) {
        if (geoTex[gi] == null) return;
        var layers = texData[gi].layers();
        int layerCount = geoTex[gi].length;

        if (layers.isEmpty()) {
            // Legacy single-layer fallback
            boolean isOpaque = texData[gi].isOpaque();
            if (opaquePass != isOpaque) return;
            if (!opaquePass) GlPreviewCanvas.applyBlendMode(texData[gi].filterMode());
            if (texUnshadedLoc >= 0) glUniform1i(texUnshadedLoc, 0);
            setUVTransform(layerUVXforms, gi, 0);
            drawSingleLayer(gi, 0, texData[gi].filterMode(), 1.0f, geoVao, geoIndexCount, geoTex, geoAlpha);
            return;
        }

        for (int li = 0; li < Math.min(layerCount, layers.size()); li++) {
            var layer = layers.get(li);
            boolean layerOpaque = layer.isOpaque();
            if (opaquePass != layerOpaque) continue;

            if (!opaquePass) {
                if (layer.replaceableId() == 2) {
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE);
                } else {
                    GlPreviewCanvas.applyBlendMode(layer.filterMode());
                }
            }
            if (texUnshadedLoc >= 0) glUniform1i(texUnshadedLoc, layer.isUnshaded() ? 1 : 0);
            setUVTransform(layerUVXforms, gi, li);
            float la = (layerAlphaArr != null && gi < layerAlphaArr.length
                    && layerAlphaArr[gi] != null && li < layerAlphaArr[gi].length)
                    ? layerAlphaArr[gi][li] : layer.alpha();
            drawSingleLayer(gi, li, layer.filterMode(), la, geoVao, geoIndexCount, geoTex, geoAlpha);
        }
    }

    private void drawSingleLayer(int gi, int li, int filterMode, float layerAlpha,
                                  int[] geoVao, int[] geoIndexCount, int[][] geoTex, float geoAlpha) {
        boolean hasTex = geoTex[gi] != null && li < geoTex[gi].length && geoTex[gi][li] != 0;
        glUniform1i(texHasTexLoc, hasTex ? 1 : 0);
        float threshold = (filterMode == 1) ? 0.75f : 0.0f;
        glUniform1f(texAlphaThreshLoc, threshold);
        glUniform1f(texAlphaLoc, geoAlpha * layerAlpha);
        if (hasTex) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, geoTex[gi][li]);
            glUniform1i(texSamplerLoc, 0);
        }
        glBindVertexArray(geoVao[gi]);
        glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
    }

    private static float[] buildThumbnailModelView(float cx, float cy, float cz, float boundsRadius,
                                                     float yaw, float pitch) {
        float r = Math.max(30f, boundsRadius);
        float modelScale = GlPreviewCanvas.clamp(120f / r, 0.005f, 500f);

        // Retera convention: distance = boundsRadius * sqrt(2) * 2
        float scaledRadius = r * modelScale;
        float distance = scaledRadius * (float) Math.sqrt(2) * 2f;

        float[] mv = GlPreviewCanvas.identity();
        mv = GlPreviewCanvas.translate(mv, 0f, 0f, -distance);
        mv = GlPreviewCanvas.rotateX(mv, pitch);
        mv = GlPreviewCanvas.rotateY(mv, yaw);
        mv = GlPreviewCanvas.rotateX(mv, -90f); // Z-up -> Y-up
        mv = GlPreviewCanvas.scale(mv, modelScale);
        mv = GlPreviewCanvas.translate(mv, -cx, -cy, -cz);
        return mv;
    }

    private static boolean hasKeysInRange(AnimTrack track, long start, long end) {
        for (long f : track.frames()) {
            if (f >= start && f <= end) return true;
        }
        return false;
    }

    /** Find the first sequence whose name contains the given string (case-insensitive). */
    private static SequenceInfo findSequence(List<SequenceInfo> sequences, String name) {
        String needle = name.toLowerCase(Locale.ROOT);
        for (SequenceInfo seq : sequences) {
            if (seq.name().toLowerCase(Locale.ROOT).contains(needle)) return seq;
        }
        return null;
    }

    // ── Texture loading helpers ──────────────────────────────────────────────

    private static int loadLayerTexture(String texPath, int replId, int tc, Path modelDir, Path rootDir) {
        if (replId == 1 && !texPath.isEmpty()) {
            return loadTeamColorTexture(texPath, tc, modelDir, rootDir);
        } else if (replId == 1) {
            return loadTeamColorSwatchTexture(tc, modelDir, rootDir);
        } else if (replId == 2) {
            String glowPath = String.format("ReplaceableTextures\\TeamGlow\\TeamGlow%02d.blp", tc);
            int tex = loadGlTexture(glowPath, modelDir, rootDir);
            if (tex == 0 && !texPath.isEmpty()) tex = loadGlTexture(texPath, modelDir, rootDir);
            if (tex == 0) tex = createSolidColorTexture(resolveTeamColorRgb(tc, modelDir, rootDir));
            return tex;
        } else if (!texPath.isEmpty()) {
            return loadGlTexture(texPath, modelDir, rootDir);
        }
        return 0;
    }

    private static int loadGlTexture(String texPath, Path modelDir, Path rootDir) {
        BufferedImage img = GameDataSource.getInstance().loadTexture(texPath, modelDir, rootDir);
        if (img == null) return 0;
        return GlPreviewCanvas.uploadTexture(img);
    }

    private static int loadTeamColorTexture(String basePath, int tcIdx, Path modelDir, Path rootDir) {
        BufferedImage base = GameDataSource.getInstance().loadTexture(basePath, modelDir, rootDir);
        if (base == null) return loadTeamColorSwatchTexture(tcIdx, modelDir, rootDir);
        int[] tc = resolveTeamColorRgb(tcIdx, modelDir, rootDir);
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
                    r = (argb >> 16) & 0xFF; g = (argb >> 8) & 0xFF; b = argb & 0xFF;
                }
                int cr = Math.round(a * r + (1f - a) * tc[0]);
                int cg = Math.round(a * g + (1f - a) * tc[1]);
                int cb = Math.round(a * b + (1f - a) * tc[2]);
                result.setRGB(x, y, 0xFF000000 | (cr << 16) | (cg << 8) | cb);
            }
        }
        return GlPreviewCanvas.uploadTexture(result);
    }

    private static int loadTeamColorSwatchTexture(int tcIdx, Path modelDir, Path rootDir) {
        BufferedImage img = GameDataSource.getInstance().loadTeamColorTexture(tcIdx, modelDir, rootDir);
        if (img != null) {
            return GlPreviewCanvas.uploadTexture(img);
        }
        return createSolidColorTexture(resolveTeamColorRgb(tcIdx, modelDir, rootDir));
    }

    private static int[] resolveTeamColorRgb(int tcIdx, Path modelDir, Path rootDir) {
        int[] sampled = GameDataSource.getInstance().loadTeamColorRgb(tcIdx, modelDir, rootDir);
        if (sampled != null) {
            return sampled;
        }
        return DEFAULT_TEAM_COLORS[Math.max(0, Math.min(DEFAULT_TEAM_COLORS.length - 1, tcIdx))];
    }

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

    // ── Placeholder for models that can't be rendered ─────────────────────────

    private BufferedImage createPlaceholder(String message) {
        BufferedImage img = new BufferedImage(thumbSize, thumbSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Dark background
        g2.setColor(new java.awt.Color(30, 35, 40));
        g2.fillRect(0, 0, thumbSize, thumbSize);

        // Warning icon (triangle with !)
        int iconSize = thumbSize / 4;
        int cx = thumbSize / 2;
        int cy = thumbSize / 2 - iconSize / 3;
        g2.setColor(new java.awt.Color(180, 140, 60));
        g2.setStroke(new java.awt.BasicStroke(2f));
        int[] xPts = {cx, cx - iconSize / 2, cx + iconSize / 2};
        int[] yPts = {cy - iconSize / 2, cy + iconSize / 2, cy + iconSize / 2};
        g2.drawPolygon(xPts, yPts, 3);
        g2.setFont(g2.getFont().deriveFont(java.awt.Font.BOLD, iconSize * 0.5f));
        java.awt.FontMetrics fmIcon = g2.getFontMetrics();
        g2.drawString("!", cx - fmIcon.stringWidth("!") / 2, cy + iconSize / 4);

        // Message text (word-wrapped)
        g2.setColor(new java.awt.Color(160, 165, 170));
        g2.setFont(g2.getFont().deriveFont(java.awt.Font.PLAIN, Math.max(10f, thumbSize / 20f)));
        java.awt.FontMetrics fm = g2.getFontMetrics();
        int maxWidth = thumbSize - 16;
        int textY = cy + iconSize / 2 + fm.getHeight() + 4;
        for (String line : wrapText(message, fm, maxWidth)) {
            int tx = (thumbSize - fm.stringWidth(line)) / 2;
            g2.drawString(line, tx, textY);
            textY += fm.getHeight();
            if (textY > thumbSize - 4) break;
        }

        g2.dispose();
        return img;
    }

    private static List<String> wrapText(String text, java.awt.FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.isEmpty()) {
                line.append(word);
            } else if (fm.stringWidth(line + " " + word) <= maxWidth) {
                line.append(" ").append(word);
            } else {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    // ── Disk cache ────────────────────────────────────────────────────────────

    private static Path resolveDiskCacheDir() {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".wc3-model-explorer", "thumbcache");
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    private Path diskCachePath(Path modelPath) {
        if (DISK_CACHE_DIR == null) return null;
        // Use hash of absolute path + file size + lastModified as cache key
        try {
            String key = modelPath.toAbsolutePath().toString()
                    + "|" + Files.size(modelPath)
                    + "|" + Files.getLastModifiedTime(modelPath).toMillis()
                    + "|" + thumbSize;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", hash[i]));
            return DISK_CACHE_DIR.resolve(sb + ".png");
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private BufferedImage loadFromDiskCache(Path modelPath) {
        Path cachePath = diskCachePath(modelPath);
        if (cachePath == null || !Files.exists(cachePath)) return null;
        try {
            return ImageIO.read(cachePath.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private void saveToDiskCache(Path modelPath, BufferedImage img) {
        Path cachePath = diskCachePath(modelPath);
        if (cachePath == null) return;
        try {
            ImageIO.write(img, "png", cachePath.toFile());
        } catch (IOException ignored) {}
    }

    // ── Request record ───────────────────────────────────────────────────────

    record ThumbnailRequest(ModelAsset asset, Path rootDir, int generation, Consumer<BufferedImage> callback) {
        static final ThumbnailRequest POISON = new ThumbnailRequest(null, null, -1, null);
    }
}
