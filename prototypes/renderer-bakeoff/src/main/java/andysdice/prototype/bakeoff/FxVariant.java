package andysdice.prototype.bakeoff;

import javafx.geometry.Point3D;
import javafx.scene.AmbientLight;
import javafx.scene.DirectionalLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import javafx.scene.transform.Affine;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * PROTOTYPE. Variant A: the die drawn with JavaFX 3D (Prism's fixed-function Phong pipeline).
 *
 * <p>Uses everything JavaFX 3D offers for realism: per-pixel Phong with diffuse, specular and normal
 * ("bump") maps, two directional lights plus ambient (three is the per-shape limit) and 4x MSAA.
 * JavaFX has no shadows, so a blurred blob texture under each die stands in for one, and no
 * reflections or environment lighting at all.
 */
final class FxVariant implements Variant {
    /**
     * JavaFX's world is Y-down. The shared Y-up scene is carried over by a 180 degree turn about X,
     * (x, y, z) -> (x, -y, -z). Flipping Y alone is a reflection and rendered every numeral mirrored;
     * a rotation keeps handedness, so counter-clockwise-from-outside triangles stay front-facing.
     */
    private static final Matrix4f TO_FX = new Matrix4f().rotationX((float) Math.PI);

    private final SceneSpec spec;
    private final Textures tex;
    private final Pane root = new Pane();
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Affine cameraXf = new Affine();
    private final List<MeshView> dice = new ArrayList<>();
    private final List<Affine> diceXf = new ArrayList<>();
    private final List<MeshView> shadows = new ArrayList<>();
    private final List<Affine> shadowXf = new ArrayList<>();
    private final PhongMaterial dieMaterial = new PhongMaterial();
    private int builtLookVersion = -1;

    FxVariant(SceneSpec spec, Textures tex) {
        this.spec = spec;
        this.tex = tex;
        Group world = new Group();

        PhongMaterial felt = new PhongMaterial(Color.WHITE);
        felt.setDiffuseMap(image(tex.felt, Textures.FELT_W, Textures.FELT_H));
        felt.setSpecularColor(Color.BLACK);
        world.getChildren().add(new MeshView(toFx(spec.floor)) {{ setMaterial(felt); }});

        PhongMaterial wood = new PhongMaterial(Color.WHITE);
        wood.setDiffuseMap(image(tex.wood, Textures.WOOD_PX, Textures.WOOD_PX));
        wood.setSpecularColor(Color.gray(0.25));
        wood.setSpecularPower(20);
        for (MeshData w : spec.walls) world.getChildren().add(new MeshView(toFx(w)) {{ setMaterial(wood); }});

        // Blob shadows go after the floor so their alpha blends over it (JavaFX doesn't sort transparency).
        PhongMaterial shadowMat = new PhongMaterial(Color.BLACK);
        shadowMat.setDiffuseMap(image(tex.blobShadow, Textures.SHADOW_PX, Textures.SHADOW_PX));
        shadowMat.setSpecularColor(Color.BLACK);
        TriangleMesh shadowQuad = toFx(MeshData.floorQuad(0.5f, 0.5f, 0));
        TriangleMesh dieMesh = toFx(spec.d20.mesh);
        for (int i = 0; i < 20; i++) {
            MeshView s = new MeshView(shadowQuad);
            s.setMaterial(shadowMat);
            Affine sx = new Affine();
            s.getTransforms().add(sx);
            shadows.add(s);
            shadowXf.add(sx);
            world.getChildren().add(s);
        }
        dieMaterial.setBumpMap(image(tex.atlasNormal, Textures.ATLAS_PX, Textures.ATLAS_PX));
        dieMaterial.setSpecularColor(Color.WHITE);
        for (int i = 0; i < 20; i++) {
            MeshView d = new MeshView(dieMesh);
            d.setMaterial(dieMaterial);
            d.setCullFace(CullFace.BACK);
            Affine dx = new Affine();
            d.getTransforms().add(dx);
            dice.add(d);
            diceXf.add(dx);
            world.getChildren().add(d);
        }

        DirectionalLight key = new DirectionalLight(Color.color(1.0, 0.96, 0.90));
        key.setDirection(fxDir(new Vector3f(SceneSpec.KEY_DIR).negate()));
        DirectionalLight fill = new DirectionalLight(Color.color(0.55 * 0.6, 0.62 * 0.6, 0.75 * 0.6));
        fill.setDirection(fxDir(new Vector3f(SceneSpec.FILL_DIR).negate()));
        AmbientLight ambient = new AmbientLight(Color.gray(SceneSpec.AMBIENT));
        world.getChildren().addAll(key, fill, ambient);

        camera.setFieldOfView(SceneSpec.FOV_Y_DEG);
        camera.setNearClip(1);
        camera.setFarClip(3000);
        camera.getTransforms().add(cameraXf);
        world.getChildren().add(camera);

        SubScene sub = new SubScene(world, 100, 100, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.rgb(18, 18, 20));
        sub.setCamera(camera);
        sub.widthProperty().bind(root.widthProperty());
        sub.heightProperty().bind(root.heightProperty());
        root.getChildren().add(sub);
        update();
    }

    @Override public String key() { return "A"; }

    @Override public String title() { return "JavaFX 3D (Phong)"; }

    @Override public Node node() { return root; }

    @Override
    public void update() {
        if (builtLookVersion != spec.lookVersion) {
            builtLookVersion = spec.lookVersion;
            Look look = spec.look();
            dieMaterial.setDiffuseMap(image(tex.bakeFxDiffuse(look), Textures.ATLAS_PX, Textures.ATLAS_PX));
            dieMaterial.setSpecularMap(image(tex.bakeFxSpecular(look), Textures.ATLAS_PX, Textures.ATLAS_PX));
            dieMaterial.setSpecularPower(look.fxSpecularPower());
        }
        SceneSpec.Frame f = spec.frame();
        setCamera(f.eye(), f.target());
        for (int i = 0; i < 20; i++) {
            boolean on = i < f.dice().size();
            dice.get(i).setVisible(on);
            shadows.get(i).setVisible(on);
            if (!on) continue;
            setAffine(diceXf.get(i), fxSpace(f.dice().get(i)));
            // Project the die centre onto the felt along the key light, and grow/soften with height.
            Vector3f c = f.diceCentres().get(i);
            Vector3f k = SceneSpec.KEY_DIR;
            float sx = c.x - k.x * c.y / k.y, sz = c.z - k.z * c.y / k.y;
            float size = 2.6f * D20.INRADIUS + (c.y - D20.INRADIUS) * 0.8f;
            setAffine(shadowXf.get(i), fxSpace(new Matrix4f().translation(sx, 0.08f, sz).scale(size, 1, size)));
        }
    }

    @Override
    public String stats() {
        return "Prism pipeline: " + com.sun.prism.GraphicsPipeline.getPipeline().getClass().getSimpleName()
                + "   MSAA: SceneAntialiasing.BALANCED";
    }

    @Override public void dispose() {
        // Nothing to free explicitly: Prism releases textures when the nodes are garbage collected.
    }

    /** Camera frame in JavaFX space: x right, y down, z forward (all right-handed). */
    private void setCamera(Vector3f eyeUp, Vector3f targetUp) {
        Vector3f eye = fxSpace(eyeUp), target = fxSpace(targetUp);
        Vector3f fwd = new Vector3f(target).sub(eye).normalize();
        Vector3f right = new Vector3f(0, 1, 0).cross(fwd).normalize();
        Vector3f down = new Vector3f(fwd).cross(right);
        cameraXf.setToTransform(right.x, down.x, fwd.x, eye.x, right.y, down.y, fwd.y, eye.y, right.z, down.z, fwd.z, eye.z);
    }

    private static void setAffine(Affine a, Matrix4f m) {
        a.setToTransform(m.m00(), m.m10(), m.m20(), m.m30(), m.m01(), m.m11(), m.m21(), m.m31(), m.m02(), m.m12(), m.m22(), m.m32());
    }

    /** R * m * R (R is its own inverse): the same transform expressed in JavaFX space. */
    private static Matrix4f fxSpace(Matrix4f m) {
        return new Matrix4f(TO_FX).mul(m).mul(TO_FX);
    }

    private static Vector3f fxSpace(Vector3f v) {
        return new Vector3f(v.x, -v.y, -v.z);
    }

    private static Point3D fxDir(Vector3f yUp) {
        return new Point3D(yUp.x, -yUp.y, -yUp.z);
    }

    private static TriangleMesh toFx(MeshData m) {
        TriangleMesh t = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD);
        float[] p = m.positions.clone(), n = m.normals.clone();
        for (int i = 1; i < p.length; i += 3) {
            p[i] = -p[i];
            n[i] = -n[i];
            p[i + 1] = -p[i + 1];
            n[i + 1] = -n[i + 1];
        }
        t.getPoints().setAll(p);
        t.getNormals().setAll(n);
        t.getTexCoords().setAll(m.uvs);
        int[] faces = new int[m.indices.length * 3];
        for (int tri = 0; tri < m.triangleCount(); tri++) {
            int a = m.indices[tri * 3], b = m.indices[tri * 3 + 1], c = m.indices[tri * 3 + 2];
            int[] v = {a, b, c};
            for (int k = 0; k < 3; k++) {
                faces[tri * 9 + k * 3] = v[k];
                faces[tri * 9 + k * 3 + 1] = v[k];
                faces[tri * 9 + k * 3 + 2] = v[k];
            }
        }
        t.getFaces().setAll(faces);
        return t;
    }

    private static Image image(int[] argb, int w, int h) {
        WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return img;
    }
}
