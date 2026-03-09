package com.war3.viewer.app;

import com.hiveworkshop.rms.editor.model.util.FilterMode;
import com.hiveworkshop.rms.parsers.mdlx.MdlxGeoset;
import com.hiveworkshop.rms.parsers.mdlx.MdlxLayer;
import com.hiveworkshop.rms.parsers.mdlx.MdlxMaterial;
import com.hiveworkshop.rms.parsers.mdlx.MdlxModel;
import com.hiveworkshop.rms.parsers.mdlx.MdlxTexture;
import com.hiveworkshop.rms.parsers.mdlx.MdxLoadSave;
import com.war3.viewer.app.datasource.GameDataSource;
import com.war3.viewer.app.settings.AppSettings;
import de.wc3data.image.BlpFile;
import de.wc3data.image.TgaFile;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MdxPreviewFactory {
    private static final Color PREVIEW_BACKGROUND   = Color.web("#e9eef5");
    private static final Color DEFAULT_MATERIAL_COLOR = Color.web("#d0d8e4");

    public static final Color[] TEAM_COLORS = {
        Color.web("#FF0303"), Color.web("#0042FF"), Color.web("#1CE6B9"),
        Color.web("#540081"), Color.web("#FFFC01"), Color.web("#FE890D"),
        Color.web("#1FBF00"), Color.web("#E55BB0"), Color.web("#959697"),
        Color.web("#7EBFF1"), Color.web("#106246"), Color.web("#4E2A04"),
    };
    public static final String[] TEAM_COLOR_NAMES = {
        "P1 Red", "P2 Blue", "P3 Teal", "P4 Purple",
        "P5 Yellow", "P6 Orange", "P7 Green", "P8 Pink",
        "P9 Gray", "P10 Lt.Blue", "P11 Dk.Green", "P12 Brown",
    };

    private final Map<Path, Image>   textureCache    = new ConcurrentHashMap<>();
    private final Map<String, Image> gameDataCache   = new ConcurrentHashMap<>();
    /** Composited images keyed by "normalizedTexturePath|tcN" for team-colour blending. */
    private final Map<String, Image> tcCompositeCache = new ConcurrentHashMap<>();
    private final Image fallbackTexture = createFallbackTexture();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public MdlxModel loadModel(final Path mdxFile) throws IOException {
        final byte[] bytes = Files.readAllBytes(mdxFile);
        final MdlxModel model = new MdlxModel();
        MdxLoadSave.loadMdx(model, ByteBuffer.wrap(bytes));
        return model;
    }

    public SubScene buildSubScene(
            final MdlxModel model,
            final Path mdxFile,
            final Path rootDirectory,
            final double width,
            final double height) {

        final Group modelGroup = buildModelGroup(model, mdxFile, rootDirectory);

        // SD models are pre-lit: one full-white AmbientLight = texel * (1,1,1,1), matching
        // the reference simpleDiffuse.frag which has all dynamic lighting commented out.
        final AmbientLight ambient = new AmbientLight(Color.WHITE);

        final Group world = new Group(modelGroup, ambient);

        final SubScene subScene = new SubScene(world, width, height, true, SceneAntialiasing.BALANCED);
        subScene.setFill(PREVIEW_BACKGROUND);

        final PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(9000);
        camera.setFieldOfView(31);
        camera.setTranslateZ(-380);
        camera.setTranslateY(-18);
        subScene.setCamera(camera);

        return subScene;
    }

    // -------------------------------------------------------------------------
    // Methods exposed for ModelDetailDialog
    // -------------------------------------------------------------------------

    public static final class BoundsInfo {
        public double minX, minY, minZ, maxX, maxY, maxZ;
        public boolean initialized;
    }

    public BoundsInfo computeBounds(final MdlxModel model) {
        final BoundsInfo bounds = new BoundsInfo();
        for (final MdlxGeoset geoset : model.geosets) {
            if (geoset.vertices == null) continue;
            final int n = geoset.vertices.length / 3;
            for (int i = 0; i < n; i++) {
                final float x = geoset.vertices[i * 3];
                final float y = -geoset.vertices[i * 3 + 2];  // JavaFX space
                final float z = geoset.vertices[i * 3 + 1];
                if (!bounds.initialized) {
                    bounds.minX = bounds.maxX = x;
                    bounds.minY = bounds.maxY = y;
                    bounds.minZ = bounds.maxZ = z;
                    bounds.initialized = true;
                } else {
                    if (x < bounds.minX) bounds.minX = x; if (x > bounds.maxX) bounds.maxX = x;
                    if (y < bounds.minY) bounds.minY = y; if (y > bounds.maxY) bounds.maxY = y;
                    if (z < bounds.minZ) bounds.minZ = z; if (z > bounds.maxZ) bounds.maxZ = z;
                }
            }
        }
        return bounds;
    }

    public void normalizeGroup(final Group raw, final BoundsInfo bounds) {
        normalize(raw, bounds);
    }

    public PhongMaterial resolveMaterialPublic(
            final MdlxModel model, final MdlxGeoset geoset,
            final Path mdxFile, final Path rootDirectory) {
        return resolveMaterial(model, geoset, mdxFile, rootDirectory, 0);
    }

    public PhongMaterial resolveMaterialPublic(
            final MdlxModel model, final MdlxGeoset geoset,
            final Path mdxFile, final Path rootDirectory, final int teamColorIndex) {
        return resolveMaterial(model, geoset, mdxFile, rootDirectory, teamColorIndex);
    }

    /**
     * Returns a human-readable diagnostic report for the model's textures and
     * geosets — useful for debugging missing/incorrect textures.
     */
    public String buildTextureDiagnostics(
            final MdlxModel model, final Path mdxFile, final Path rootDirectory) {
        final StringBuilder sb = new StringBuilder();

        // ── Texture list ──────────────────────────────────────────────────────
        sb.append("=== Textures (").append(model.textures.size()).append(") ===\n");
        for (int ti = 0; ti < model.textures.size(); ti++) {
            final MdlxTexture tex = model.textures.get(ti);
            sb.append('[').append(ti).append("] ");
            if (tex.replaceableId != 0) {
                sb.append("Replaceable #").append(tex.replaceableId)
                  .append(" (team color / glow — no static path)\n");
                continue;
            }
            final String p = (tex.path == null) ? "" : tex.path.trim();
            if (p.isEmpty()) {
                sb.append("(empty path)\n");
                continue;
            }
            sb.append(p).append('\n');
            final Optional<Path> disk = resolveTexturePath(p, mdxFile, rootDirectory);
            if (disk.isPresent()) {
                sb.append("    → DISK ✓  ").append(disk.get()).append('\n');
            } else {
                final GameDataSource gds = GameDataSource.get();
                if (gds.isEmpty()) {
                    sb.append("    → MISSING (CASC/MPQ not configured)\n");
                } else {
                    boolean found = false;
                    for (final String c : textureVariants(p.replace('/', '\\'))) {
                        if (gds.has(c)) {
                            sb.append("    → CASC ✓  ").append(c).append('\n');
                            found = true;
                            break;
                        }
                    }
                    if (!found) sb.append("    → MISSING (checked disk + CASC)\n");
                }
            }
        }

        // ── Geosets ───────────────────────────────────────────────────────────
        int lod0Count = 0, skippedLod = 0;
        for (final MdlxGeoset g : model.geosets) {
            if (g.lod == 0) lod0Count++; else skippedLod++;
        }
        sb.append("\n=== Geosets: ").append(lod0Count)
          .append(" rendered (LOD=0)")
          .append(skippedLod > 0 ? ", " + skippedLod + " skipped (LOD>0)" : "")
          .append(" ===\n");

        for (int gi = 0; gi < model.geosets.size(); gi++) {
            final MdlxGeoset g = model.geosets.get(gi);
            if (g.lod != 0) continue;
            final int verts = g.vertices == null ? 0 : g.vertices.length / 3;
            final int faces = g.faces   == null ? 0 : g.faces.length / 3;
            sb.append("Geoset ").append(gi)
              .append("  verts=").append(verts)
              .append("  tris=").append(faces)
              .append("  mat=").append(g.materialId)
              .append('\n');

            final int matIdx = (int) g.materialId;
            if (matIdx < 0 || matIdx >= model.materials.size()) {
                sb.append("    !! material index out of range\n");
                continue;
            }
            final MdlxMaterial mat = model.materials.get(matIdx);
            for (int li = 0; li < mat.layers.size(); li++) {
                final MdlxLayer layer = mat.layers.get(li);
                sb.append("    Layer ").append(li)
                  .append("  filter=").append(layer.filterMode)
                  .append("  texId=").append(layer.textureId);
                if (layer.textureId >= 0 && layer.textureId < model.textures.size()) {
                    final MdlxTexture tex = model.textures.get(layer.textureId);
                    if (tex.replaceableId != 0) {
                        sb.append("  [replaceable#").append(tex.replaceableId).append(']');
                    } else {
                        final String tp = (tex.path == null) ? "" : tex.path.trim();
                        sb.append("  [").append(tp.isEmpty() ? "(empty)" : tp).append(']');
                        // Quick load-status check for layer 0 only (most relevant)
                        if (li == 0 && !tp.isEmpty()) {
                            final Optional<Path> disk = resolveTexturePath(tp, mdxFile, rootDirectory);
                            if (disk.isPresent()) {
                                sb.append(" DISK✓");
                            } else {
                                final GameDataSource gds = GameDataSource.get();
                                if (!gds.isEmpty()) {
                                    boolean found = false;
                                    for (final String c : textureVariants(tp.replace('/', '\\'))) {
                                        if (gds.has(c)) { found = true; break; }
                                    }
                                    sb.append(found ? " CASC✓" : " MISSING");
                                } else {
                                    sb.append(" MISSING(no CASC)");
                                }
                            }
                        }
                    }
                } else {
                    sb.append("  !! texId out of range");
                }
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal model building
    // -------------------------------------------------------------------------

    private Group buildModelGroup(final MdlxModel model, final Path mdxFile, final Path rootDirectory) {
        final BoundsAccumulator bounds = new BoundsAccumulator();

        // Two-pass rendering matching the reference GeosetRenderer:
        //   Pass 1 – opaque (NONE/TRANSPARENT): written to depth buffer first so transparent layers
        //            can correctly test depth against solid geometry.
        //   Pass 2 – transparent (BLEND/ADDITIVE/…): depth-tested against pass-1 geometry but not
        //            necessarily against each other (no z-sort needed for a preview thumbnail).
        final List<MeshView> opaqueMeshes     = new ArrayList<>();
        final List<MeshView> transparentMeshes = new ArrayList<>();

        for (final MdlxGeoset geoset : model.geosets) {
            if (geoset.lod != 0) continue; // skip lower LOD levels (1=SD, 2=...); 0=HD/only level
            final MeshView meshView = buildGeosetMesh(geoset, model, mdxFile, rootDirectory, bounds);
            if (meshView == null) continue;
            if (isOpaqueGeoset(geoset, model)) opaqueMeshes.add(meshView);
            else                               transparentMeshes.add(meshView);
        }

        final Group raw = new Group();
        raw.getChildren().addAll(opaqueMeshes);
        raw.getChildren().addAll(transparentMeshes);

        if (raw.getChildren().isEmpty()) return createEmptyPreviewGroup();

        normalize(raw, bounds);

        final AppSettings s = AppSettings.get();
        final Group oriented = new Group(raw);
        oriented.getTransforms().addAll(
                new Rotate(s.getPreviewElevation(), Rotate.X_AXIS),
                new Rotate(s.getPreviewAzimuth(),   Rotate.Y_AXIS)
        );
        return oriented;
    }

    private MeshView buildGeosetMesh(
            final MdlxGeoset geoset,
            final MdlxModel model,
            final Path mdxFile,
            final Path rootDirectory,
            final BoundsAccumulator bounds) {
        if (geoset.vertices == null || geoset.vertices.length < 9 ||
                geoset.faces == null || geoset.faces.length < 3) return null;

        final int vertexCount = geoset.vertices.length / 3;
        final float[] points = new float[geoset.vertices.length];

        for (int i = 0; i < vertexCount; i++) {
            final float x = geoset.vertices[i * 3];
            final float y = -geoset.vertices[i * 3 + 2];
            final float z = geoset.vertices[i * 3 + 1];
            points[i * 3] = x; points[i * 3 + 1] = y; points[i * 3 + 2] = z;
            bounds.include(x, y, z);
        }

        final float[] texCoords = buildTexCoords(geoset, vertexCount);
        final boolean hasUv = texCoords.length > 2;
        final int uvCount = texCoords.length / 2;

        final List<Integer> faces = new ArrayList<>();
        for (int i = 0; i + 2 < geoset.faces.length; i += 3) {
            final int a = geoset.faces[i], b = geoset.faces[i + 1], c = geoset.faces[i + 2];
            if (!isValid(a, vertexCount) || !isValid(b, vertexCount) || !isValid(c, vertexCount)) continue;
            faces.add(a); faces.add(hasUv && a < uvCount ? a : 0);
            faces.add(b); faces.add(hasUv && b < uvCount ? b : 0);
            faces.add(c); faces.add(hasUv && c < uvCount ? c : 0);
        }
        if (faces.isEmpty()) return null;

        final TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(texCoords);
        mesh.getFaces().setAll(toIntArray(faces));

        final MeshView mv = new MeshView(mesh);
        mv.setCullFace(CullFace.NONE);
        mv.setMaterial(resolveMaterial(model, geoset, mdxFile, rootDirectory, 0));
        return mv;
    }

    private boolean isValid(final int index, final int count) {
        return index >= 0 && index < count;
    }

    /**
     * Returns true if the geoset's dominant layer is opaque (NONE or TRANSPARENT filter mode).
     * Matches the reference GeosetRenderer.isOpaqueLayer() logic:
     *   NONE / TRANSPARENT → opaque pass (depth write on, alpha test)
     *   BLEND / ADDITIVE / ADDALPHA / MODULATE / MODULATE2X → transparent pass
     */
    public boolean isOpaqueGeoset(final MdlxGeoset geoset, final MdlxModel model) {
        final int matIdx = (int) geoset.materialId;
        if (matIdx < 0 || matIdx >= model.materials.size()) return true;
        final MdlxMaterial mat = model.materials.get(matIdx);
        for (final MdlxLayer layer : mat.layers) {
            if (layer.textureId < 0 || layer.textureId >= model.textures.size()) continue;
            final MdlxTexture tex = model.textures.get(layer.textureId);
            // Use the first base-texture layer's filter mode to classify the geoset
            if (tex.replaceableId == 0 && tex.path != null && !tex.path.trim().isEmpty()) {
                return layer.filterMode == FilterMode.NONE
                        || layer.filterMode == FilterMode.TRANSPARENT;
            }
            // A replaceable-ID layer (TC/glow) is treated as opaque for ordering purposes
            if (tex.replaceableId == 1) return true;
            if (tex.replaceableId == 2) return false; // glow = additive = transparent pass
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Normalization
    // -------------------------------------------------------------------------

    private void normalize(final Group raw, final BoundsAccumulator acc) {
        if (!acc.initialized) return;
        doNormalize(raw, acc.minX, acc.maxX, acc.minY, acc.maxY, acc.minZ, acc.maxZ);
    }

    private void normalize(final Group raw, final BoundsInfo b) {
        if (!b.initialized) return;
        doNormalize(raw, b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ);
    }

    private static void doNormalize(final Group raw,
                                     final double minX, final double maxX,
                                     final double minY, final double maxY,
                                     final double minZ, final double maxZ) {
        raw.setTranslateX(-(minX + maxX) / 2.0);
        raw.setTranslateY(-(minY + maxY) / 2.0);
        raw.setTranslateZ(-(minZ + maxZ) / 2.0);
        final double maxDim = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        final double scale = maxDim > 0.0001 ? 170.0 / maxDim : 1.0;
        raw.setScaleX(scale); raw.setScaleY(scale); raw.setScaleZ(scale);
    }

    // -------------------------------------------------------------------------
    // Material / texture resolution
    // -------------------------------------------------------------------------

    private PhongMaterial resolveMaterial(
            final MdlxModel model, final MdlxGeoset geoset,
            final Path mdxFile, final Path rootDirectory, final int teamColorIndex) {

        final int matIdx = (int) geoset.materialId;
        if (matIdx < 0 || matIdx >= model.materials.size()) return defaultMaterial();
        final MdlxMaterial mat = model.materials.get(matIdx);
        if (mat.layers.isEmpty()) return defaultMaterial();

        // Scan layers: find first team-color layer and first non-replaceable base-texture layer.
        int tcReplId = 0;
        MdlxLayer baseLayer = null;
        boolean hasTeamColor = false;
        FilterMode filterMode = FilterMode.NONE;

        for (final MdlxLayer layer : mat.layers) {
            if (layer.textureId < 0 || layer.textureId >= model.textures.size()) continue;
            final MdlxTexture tex = model.textures.get(layer.textureId);
            if ((tex.replaceableId == 1 || tex.replaceableId == 2) && !hasTeamColor) {
                hasTeamColor = true;
                tcReplId = tex.replaceableId;
            } else if (tex.replaceableId == 0 && tex.path != null && !tex.path.trim().isEmpty()
                    && baseLayer == null) {
                baseLayer = layer;
                filterMode = layer.filterMode;
            }
        }

        // ── No team color: resolve base texture normally ──────────────────────
        if (!hasTeamColor) {
            if (baseLayer != null) {
                return resolveLayerMaterial(
                        model.textures.get(baseLayer.textureId).path, mdxFile, rootDirectory,
                        filterMode);
            }
            return defaultMaterial();
        }

        final int tcIdx = Math.max(0, Math.min(teamColorIndex, TEAM_COLORS.length - 1));
        final Color tcColor = TEAM_COLORS[tcIdx];

        // ── Team color only (no base texture): load the actual replaceable texture ──
        // replaceableId=1 → ReplaceableTextures\TeamColor\TeamColor##.blp
        // replaceableId=2 → ReplaceableTextures\TeamGlow\TeamGlow##.blp  (additive in WC3: GL_SRC_ALPHA/GL_ONE)
        if (baseLayer == null) {
            final String replPath = replaceableTexturePath(tcReplId, tcIdx);
            final Image replTex = loadFromGameData(replPath);
            if (tcReplId == 2) {
                // Team glow: additive blend — simulate with self-illumination over black diffuse
                final PhongMaterial m = new PhongMaterial(Color.BLACK);
                m.setSelfIlluminationMap(replTex);
                return m;
            }
            final PhongMaterial m = new PhongMaterial(tcColor);
            m.setDiffuseMap(replTex);
            return m;
        }

        // ── Both TC and base texture: composite TC under the base alpha ────────
        // Formula (per pixel): result = base_alpha × base_rgb + (1 − base_alpha) × TC_rgb
        // This matches the classic WC3 GPU render:
        //   layer[background] = TC (filterMode=NONE, opaque solid)
        //   layer[foreground] = base texture (filterMode=BLEND, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        final String basePath = model.textures.get(baseLayer.textureId).path;
        final String cacheKey = buildRawCacheKey(basePath, mdxFile, rootDirectory) + "|tc" + tcIdx;

        Image compositeImg = tcCompositeCache.computeIfAbsent(cacheKey, k -> {
            final BufferedImage rawImg = loadRawBufferedImage(basePath, mdxFile, rootDirectory);
            if (rawImg == null) return fallbackTexture;
            return SwingFXUtils.toFXImage(compositeTeamColor(rawImg, tcColor), null);
        });

        // Apply filter mode: TRANSPARENT requires hard alpha cutout at 0.75
        if (filterMode == FilterMode.TRANSPARENT) {
            final Image raw = compositeImg;
            compositeImg = tcCompositeCache.computeIfAbsent(cacheKey + "|cutout",
                    k -> applyAlphaCutout(raw, 0.75f));
        }

        final PhongMaterial m = new PhongMaterial(Color.WHITE);
        m.setDiffuseMap(compositeImg);
        if (filterMode == FilterMode.ADDITIVE || filterMode == FilterMode.ADDALPHA) {
            m.setSelfIlluminationMap(compositeImg);
        }
        return m;
    }

    /**
     * Returns the CASC path for the WC3 built-in replaceable textures.
     * The zero-padded two-digit index matches the WC3 naming convention: 00..11.
     */
    private static String replaceableTexturePath(final int replaceableId, final int tcIndex) {
        final String idx = String.format("%02d", Math.max(0, Math.min(tcIndex, 11)));
        return switch (replaceableId) {
            case 1 -> "ReplaceableTextures\\TeamColor\\TeamColor" + idx + ".blp";
            case 2 -> "ReplaceableTextures\\TeamGlow\\TeamGlow" + idx + ".blp";
            default -> "";
        };
    }

    private PhongMaterial defaultMaterial() {
        final PhongMaterial m = new PhongMaterial(DEFAULT_MATERIAL_COLOR);
        m.setDiffuseMap(fallbackTexture);
        return m;
    }

    private PhongMaterial resolveLayerMaterial(
            final String path, final Path mdxFile, final Path rootDirectory,
            final FilterMode filterMode) {
        final Optional<Path> diskPath = resolveTexturePath(path, mdxFile, rootDirectory);
        Image tex;
        if (diskPath.isPresent()) {
            tex = textureCache.computeIfAbsent(
                    diskPath.get().toAbsolutePath().normalize(), this::loadTextureFromDisk);
        } else {
            tex = loadFromGameData(path);
        }

        // Apply filter mode:
        // TRANSPARENT → hard alpha cutout at 0.75 (matches GL_ALPHA_TEST > 0.75 in reference)
        // ADDITIVE / ADDALPHA → additive blend (GL_SRC_ALPHA/GL_ONE) — simulate with self-illumination
        //   over a black diffuse so only the bright pixels of the texture are visible
        // BLEND / NONE / others → use texture alpha as-is; BLEND keeps soft edges
        if (filterMode == FilterMode.TRANSPARENT) {
            final Image raw = tex;
            tex = tcCompositeCache.computeIfAbsent(
                    buildRawCacheKey(path, mdxFile, rootDirectory) + "|cutout",
                    k -> applyAlphaCutout(raw, 0.75f));
        }

        final PhongMaterial m;
        if (filterMode == FilterMode.ADDITIVE || filterMode == FilterMode.ADDALPHA) {
            // Additive: black diffuse so only the self-illuminated texture adds to the scene
            m = new PhongMaterial(Color.BLACK);
            m.setSelfIlluminationMap(tex);
        } else {
            // Use WHITE diffuse so the texture renders at its true colours without any tint.
            // (DEFAULT_MATERIAL_COLOR is only used when no texture is available at all.)
            m = new PhongMaterial(Color.WHITE);
            m.setDiffuseMap(tex);
        }
        return m;
    }

    private String buildRawCacheKey(
            final String rawPath, final Path mdxFile, final Path rootDirectory) {
        final Optional<Path> disk = resolveTexturePath(rawPath, mdxFile, rootDirectory);
        return disk.isPresent()
                ? disk.get().toAbsolutePath().normalize().toString()
                : (rawPath == null ? "" : rawPath.toLowerCase(Locale.US));
    }

    private Image loadFromGameData(final String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return fallbackTexture;
        final GameDataSource gds = GameDataSource.get();
        if (gds.isEmpty()) return fallbackTexture;
        // Normalize path to WC3 format (backslash, lowercase)
        final String wc3Path = rawPath.replace('/', '\\');
        return gameDataCache.computeIfAbsent(wc3Path.toLowerCase(Locale.US), k -> {
            try {
                for (final String candidate : textureVariants(wc3Path)) {
                    if (gds.has(candidate)) {
                        try (InputStream is = gds.getResourceAsStream(candidate)) {
                            if (is == null) continue;
                            final BufferedImage img = de.wc3data.image.BlpFile.read(candidate, is);
                            if (img != null) return SwingFXUtils.toFXImage(img, null);
                        }
                    }
                }
            } catch (final Exception ignored) {}
            return fallbackTexture;
        });
    }

    private static List<String> textureVariants(final String path) {
        final List<String> variants = new ArrayList<>();
        variants.add(path);
        final int dot = path.lastIndexOf('.');
        final String base = dot >= 0 ? path.substring(0, dot) : path;
        for (final String ext : new String[]{".blp", ".dds", ".tga", ".png"}) {
            final String candidate = base + ext;
            if (!candidate.equalsIgnoreCase(path)) variants.add(candidate);
        }
        return variants;
    }

    private Optional<Path> resolveTexturePath(
            final String rawTextureReference, final Path mdxFile, final Path rootDirectory) {
        if (rawTextureReference == null) return Optional.empty();
        String normalized = rawTextureReference.trim();
        if (normalized.isEmpty()) return Optional.empty();
        normalized = normalized.replace('\\', '/');

        final Path relative;
        try {
            relative = Paths.get(normalized.replace('/', java.io.File.separatorChar));
        } catch (final InvalidPathException ignored) {
            return Optional.empty();
        }

        final Set<Path> candidates = new LinkedHashSet<>();
        if (relative.isAbsolute()) candidates.add(relative);

        final Path modelDir = mdxFile.getParent();
        if (modelDir != null) candidates.add(modelDir.resolve(relative));
        if (rootDirectory != null) candidates.add(rootDirectory.resolve(relative));

        final Path fileName = relative.getFileName();
        if (fileName != null) {
            if (modelDir != null) candidates.add(modelDir.resolve(fileName));
            if (rootDirectory != null) candidates.add(rootDirectory.resolve(fileName));
        }

        final List<Path> expanded = new ArrayList<>();
        for (final Path c : candidates) {
            expanded.add(c);
            expanded.addAll(withAlternateExtensions(c));
        }

        for (final Path candidate : expanded) {
            final Path abs = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(abs)) return Optional.of(abs);
        }
        return Optional.empty();
    }

    private List<Path> withAlternateExtensions(final Path path) {
        final List<Path> alts = new ArrayList<>();
        final String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        final int dot = fileName.lastIndexOf('.');
        final String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        if (base.isEmpty()) return alts;
        final Path parent = path.getParent();
        for (final String ext : new String[]{".blp", ".tga", ".png", ".jpg", ".jpeg", ".bmp"}) {
            final String altName = base + ext;
            alts.add(parent == null ? Paths.get(altName) : parent.resolve(altName));
        }
        return alts;
    }

    // -------------------------------------------------------------------------
    // Raw BufferedImage loading (used for team-colour compositing)
    // -------------------------------------------------------------------------

    /** Loads a {@link BufferedImage} from disk or game data (no FX conversion). */
    private BufferedImage loadRawBufferedImage(
            final String rawPath, final Path mdxFile, final Path rootDirectory) {
        final Optional<Path> disk = resolveTexturePath(rawPath, mdxFile, rootDirectory);
        if (disk.isPresent()) return loadRawFromDisk(disk.get());
        return loadRawFromGameData(rawPath);
    }

    private BufferedImage loadRawFromDisk(final Path path) {
        try {
            final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".blp")) {
                try (InputStream is = Files.newInputStream(path)) {
                    return BlpFile.read(path.toString(), is);
                }
            }
            if (name.endsWith(".tga")) return TgaFile.readTGA(path.toFile());
            return ImageIO.read(path.toFile());
        } catch (final Exception ignored) {
            return null;
        }
    }

    private BufferedImage loadRawFromGameData(final String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return null;
        final GameDataSource gds = GameDataSource.get();
        if (gds.isEmpty()) return null;
        final String wc3Path = rawPath.replace('/', '\\');
        try {
            for (final String candidate : textureVariants(wc3Path)) {
                if (gds.has(candidate)) {
                    try (InputStream is = gds.getResourceAsStream(candidate)) {
                        if (is == null) continue;
                        final BufferedImage img = BlpFile.read(candidate, is);
                        if (img != null) return img;
                    }
                }
            }
        } catch (final Exception ignored) {}
        return null;
    }

    /**
     * Converts a texture's alpha channel to a hard cutout at the given threshold.
     * Pixels with alpha &lt; threshold become fully transparent; others become fully opaque.
     * Matches WC3 FilterMode.TRANSPARENT which uses {@code GL_ALPHA_TEST > 0.75}.
     */
    private static Image applyAlphaCutout(final Image source, final float threshold) {
        final int w = (int) source.getWidth();
        final int h = (int) source.getHeight();
        final WritableImage out = new WritableImage(w, h);
        final PixelReader reader = source.getPixelReader();
        final PixelWriter writer = out.getPixelWriter();
        final int thresh = Math.round(threshold * 255);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int argb = reader.getArgb(x, y);
                final int alpha = (argb >>> 24) & 0xFF;
                writer.setArgb(x, y, alpha >= thresh ? (argb | 0xFF000000) : 0x00000000);
            }
        }
        return out;
    }

    /**
     * Composites a team colour under a base texture using the base's alpha channel.
     * <p>
     * Per-pixel: {@code result = alpha × baseRGB + (1−alpha) × tcRGB}
     * <br>
     * Matches the classic WC3 two-layer GPU technique: TC layer rendered opaque first
     * (filterMode=NONE), base texture rendered on top with BLEND (GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA).
     * {@link BufferedImage#getRGB} always returns alpha=0xFF for non-alpha images,
     * so the formula degrades correctly to "show base only" when no alpha channel is present.
     */
    private static BufferedImage compositeTeamColor(final BufferedImage base, final Color tcColor) {
        final int w = base.getWidth(), h = base.getHeight();
        final BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final int tcR = (int) (tcColor.getRed()   * 255);
        final int tcG = (int) (tcColor.getGreen() * 255);
        final int tcB = (int) (tcColor.getBlue()  * 255);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int argb = base.getRGB(x, y);
                final float a = ((argb >> 24) & 0xFF) / 255f;
                final int r = (argb >> 16) & 0xFF;
                final int g = (argb >>  8) & 0xFF;
                final int b =  argb        & 0xFF;
                result.setRGB(x, y, 0xFF000000
                        | (Math.round(a * r + (1f - a) * tcR) << 16)
                        | (Math.round(a * g + (1f - a) * tcG) <<  8)
                        |  Math.round(a * b + (1f - a) * tcB));
            }
        }
        return result;
    }

    private Image loadTextureFromDisk(final Path texturePath) {
        try {
            final String fileName = texturePath.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".blp")) {
                // Use stream-based loading (same path as CASC); ImageIO.read(File) can silently
                // fail for BLP when the reader SPI only handles ImageInputStream from streams.
                try (InputStream is = Files.newInputStream(texturePath)) {
                    final BufferedImage img = BlpFile.read(texturePath.toString(), is);
                    return img != null ? SwingFXUtils.toFXImage(img, null) : fallbackTexture;
                }
            }
            if (fileName.endsWith(".tga")) {
                final BufferedImage img = TgaFile.readTGA(texturePath.toFile());
                return img != null ? SwingFXUtils.toFXImage(img, null) : fallbackTexture;
            }
            final Image image = new Image(texturePath.toUri().toString(), false);
            return image.isError() ? fallbackTexture : image;
        } catch (final Exception ignored) {
            return fallbackTexture;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static float[] buildTexCoords(final MdlxGeoset geoset, final int vertexCount) {
        if (geoset.uvSets == null || geoset.uvSets.length == 0 ||
                geoset.uvSets[0] == null || geoset.uvSets[0].length < 2) return new float[]{0f, 0f};
        final float[] source = geoset.uvSets[0];
        final int count = Math.min(vertexCount, source.length / 2);
        if (count <= 0) return new float[]{0f, 0f};
        final float[] uv = new float[count * 2];
        for (int i = 0; i < count; i++) {
            uv[i * 2]     = source[i * 2];
            uv[i * 2 + 1] = source[i * 2 + 1]; // no V-flip: WC3 MDX and JavaFX both use (0,0)=top-left
        }
        return uv;
    }

    private static int[] toIntArray(final List<Integer> values) {
        final int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private Group createEmptyPreviewGroup() {
        final WritableImage fallback = createFallbackTexture();
        final TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().addAll(-30, -30, 0, 30, -30, 0, 30, 30, 0, -30, 30, 0);
        mesh.getTexCoords().addAll(0, 1, 1, 1, 1, 0, 0, 0);
        mesh.getFaces().addAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);
        final MeshView mv = new MeshView(mesh);
        final PhongMaterial mat = new PhongMaterial(DEFAULT_MATERIAL_COLOR);
        mat.setDiffuseMap(fallback);
        mv.setMaterial(mat);
        final Group g = new Group(mv);
        g.getTransforms().addAll(new Rotate(-18, Rotate.X_AXIS), new Rotate(38, Rotate.Y_AXIS));
        return g;
    }

    private static Image createSolidColorImage(final Color color) {
        final WritableImage img = new WritableImage(2, 2);
        final PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) pw.setColor(x, y, color);
        return img;
    }

    private WritableImage createFallbackTexture() {
        final int size = 64;
        final WritableImage image = new WritableImage(size, size);
        final PixelWriter writer = image.getPixelWriter();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final boolean dark = ((x / 8) + (y / 8)) % 2 == 0;
                writer.setColor(x, y, dark ? Color.web("#8896ab") : Color.web("#c8d1df"));
            }
        }
        return image;
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    public static final class BoundsAccumulator {
        public double minX, minY, minZ, maxX, maxY, maxZ;
        public boolean initialized;

        public void include(final float x, final float y, final float z) {
            if (!initialized) {
                minX = maxX = x; minY = maxY = y; minZ = maxZ = z;
                initialized = true;
                return;
            }
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
    }
}
