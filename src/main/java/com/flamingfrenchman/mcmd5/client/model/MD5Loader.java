package com.flamingfrenchman.mcmd5.client.model;

import com.flamingfrenchman.mcmd5.Mcmd5;
import com.flamingfrenchman.mcmd5.proxy.ClientProxy;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.model.*;
import net.minecraftforge.client.model.b3d.B3DModel;
import net.minecraftforge.client.model.pipeline.UnpackedBakedQuad;
import net.minecraftforge.common.model.IModelPart;
import net.minecraftforge.common.model.IModelState;
import net.minecraftforge.common.model.Models;
import net.minecraftforge.common.model.TRSRTransformation;
import net.minecraftforge.common.model.animation.IClip;
import net.minecraftforge.common.model.animation.IJoint;
import net.minecraftforge.common.model.animation.IJointClip;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.Properties;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import javax.vecmath.Matrix4f;
import javax.vecmath.Quat4f;
import javax.vecmath.Vector2f;
import javax.vecmath.Vector3f;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public enum MD5Loader implements ICustomModelLoader {

    INSTANCE;

    private IResourceManager manager;

    private final Set<String> enabledDomains = new HashSet<>();
    private final Map<ResourceLocation, MD5Model> cache = new HashMap<>();

    public void addDomain(String domain)
    {
        enabledDomains.add(domain.toLowerCase());
        if(Mcmd5.debug)
            ((ClientProxy) Mcmd5.proxy).clientDebug(Level.INFO, "Domain " + domain + " registered for loader.");
    }

    @Override
    public void onResourceManagerReload(IResourceManager manager)
    {
        this.manager = manager;
        cache.clear();
    }

    @Override
    public boolean accepts(ResourceLocation modelLocation) {
        return enabledDomains.contains(modelLocation.getResourceDomain()) &&
                modelLocation.getResourcePath().endsWith(".dae");
    }

    @Override
    @MethodsReturnNonnullByDefault
    public IModel loadModel(ResourceLocation modelLocation) throws Exception {
        if(Mcmd5.debug)
            ((ClientProxy) Mcmd5.proxy).clientDebug(Level.INFO, "attempting to load model " + modelLocation.getResourcePath());
        ResourceLocation file = new ResourceLocation(modelLocation.getResourceDomain(), modelLocation.getResourcePath());
        if (!cache.containsKey(file))
        {
            IResource resource = null;
            try
            {
                try
                {
                    resource = manager.getResource(file);
                }
                catch (FileNotFoundException e)
                {
                    ((ClientProxy) Mcmd5.proxy).clientDebug(Level.INFO, "model failed to load; trying other locations");
                    if (modelLocation.getResourcePath().startsWith("models/block/"))
                        resource = manager.getResource(new ResourceLocation(file.getResourceDomain(), "models/item/" + file.getResourcePath().substring("models/block/".length())));
                    else if (modelLocation.getResourcePath().startsWith("models/item/"))
                        resource = manager.getResource(new ResourceLocation(file.getResourceDomain(), "models/block/" + file.getResourcePath().substring("models/item/".length())));
                    else throw e;
                }
                MD5Model.Parser parser = new MD5Model.Parser(resource.getInputStream(), manager, file);
                MD5Model model = null;
                try
                {
                    model = parser.parse();
                    cache.put(file, model);
                }
                catch (Exception e)
                {
                    cache.put(file, null);
                    throw e;
                }
            }
            finally
            {
                IOUtils.closeQuietly(resource);
            }
        }

        MD5Model model = cache.get(file);
        if(model == null) throw new ModelLoaderRegistry.LoaderException("Error loading model previously: " + file);
        return new ModelWrapper(modelLocation, model, true, true, 0);
    }

    /*
     * Represents the dynamic information associated with the model.
     * Common use case is (possibly interpolated) animation frame.
     */
    private static final class MD5State implements IModelState {
        private MD5State parent;
        @Nullable
        private final int frame;
        private final int nextFrame;
        private final float progress;
        /*
         * Returns the transformation that needs to be applied to the specific part of the model.
         * Coordinate system is determined by the part type.
         * if no part is provided, global model transformation is returned.
         */

        public Optional<TRSRTransformation> apply(Optional<? extends IModelPart> part) {
            return null;
        }
        
        public MD5State getParent() { return null; }
    }

    private static final class Animation
    {
        private final int flags;
        private final int frames;
        private final float fps;
        // first int is frame, second int is mesh index
        private final ImmutableTable<Integer, WrappedMesh, Key> keys;

        public Animation(int flags, int frames, float fps, ImmutableTable<Integer, WrappedMesh, Key> keys)
        {
            this.flags = flags;
            this.frames = frames;
            this.fps = fps;
            this.keys = keys;
        }

        public int getFlags()
        {
            return flags;
        }

        public int getFrames()
        {
            return frames;
        }

        public float getFps()
        {
            return fps;
        }

        public ImmutableTable<Integer, WrappedMesh, Key> getKeys()
        {
            return keys;
        }

        @Override
        public String toString()
        {
            return String.format("Animation [flags=%s, frames=%s, fps=%s, keys=...]", flags, frames, fps);
        }
    }

    private static final class Key
    {
        @Nullable
        private final Vector3f pos;
        @Nullable
        private final Vector3f scale;
        @Nullable
        private final Quat4f rot;

        public Key(@Nullable Vector3f pos, @Nullable Vector3f scale, @Nullable Quat4f rot)
        {
            this.pos = pos;
            this.scale = scale;
            this.rot = rot;
        }

        @Nullable
        public Vector3f getPos()
        {
            return pos;
        }

        @Nullable
        public Vector3f getScale()
        {
            return scale;
        }

        @Nullable
        public Quat4f getRot()
        {
            return rot;
        }

        @Override
        public String toString()
        {
            return String.format("Key [pos=%s, scale=%s, rot=%s]", pos, scale, rot);
        }
    }

    private static final class ModelWrapper implements IModel {
        private final ResourceLocation modelLocation;
        private final MD5Model model;
        private final ImmutableList<WrappedMesh> meshes;
        private final ImmutableMap<String, ResourceLocation> textures;
        private final boolean smooth;
        private final boolean gui3d;
        private final int defaultKey;

        public ModelWrapper(ResourceLocation modelLocation, MD5Model model, boolean smooth, boolean gui3d, int defaultKey)
        {
            this.modelLocation = modelLocation;
            this.model = model;
            this.smooth = smooth;
            this.gui3d = gui3d;
            this.defaultKey = defaultKey;
            this.textures = buildTextures(model.getMeshes());
            this.meshes = process(model);
        }

        private static ImmutableMap<String, ResourceLocation> buildTextures(ImmutableList<MD5Model.Mesh> meshes)
        {
            ImmutableMap.Builder<String, ResourceLocation> builder = ImmutableMap.builder();

            for(MD5Model.Mesh mesh : meshes) {
                String path = mesh.getTexture();
                String location = getLocation(path);
                builder.put(path, new ResourceLocation(location));
            }
            return builder.build();
        }

        private static ImmutableList<WrappedMesh> process(MD5Model model) {
            ImmutableList.Builder<WrappedMesh> builder = ImmutableList.builder();
            for(MD5Model.Mesh mesh : model.getMeshes()) {
                builder.add(generateWrappedMesh(model, mesh));
            }
            return builder.build();
        }

        private static WrappedMesh generateWrappedMesh(MD5Model model, MD5Model.Mesh mesh) {
            List<WrappedVertex> wrappedVertices = new ArrayList<>();
            ImmutableList.Builder<Integer> indices = ImmutableList.builder();
            MD5Model.Vertex[] vertices = mesh.getVertices();
            MD5Model.Weight[] weights = mesh.getWeights();
            ImmutableList<MD5Model.Joint> joints = model.getJoints();

            ImmutableList.Builder<Vector3f> posBuilder = ImmutableList.builder();
            ImmutableList.Builder<Vector3f> normBuilder = ImmutableList.builder();
            ImmutableList.Builder<Vector2f> texBuilder = ImmutableList.builder();

            for (MD5Model.Vertex vertex : vertices) {
                Vector3f vertexPos = new Vector3f();
                Vector2f vertexTextCoords = vertex.getTexCoords();

                int startWeight = vertex.getWeightStart();
                int numWeights = vertex.getNumweights();

                for (int i = startWeight; i < startWeight + numWeights; i++) {
                    MD5Model.Weight weight = weights[i];
                    MD5Model.Joint joint = joints.get(weight.getJointIndex());
                    Vector3f weightPos = weight.getPos();
                    Quat4f weightQuat = new Quat4f(weightPos.x, weightPos.y, weightPos.z, 0);
                    weightQuat.mul(joint.getRot());
                    Vector3f rotatedPos = new Vector3f(weightQuat.x, weightQuat.y, weightQuat.z);
                    Vector3f acumPos = new Vector3f(joint.getPos());
                    acumPos.add(rotatedPos);
                    acumPos.scale(weight.getBias());
                    vertexPos.add(acumPos);
                }

                wrappedVertices.add(new WrappedVertex(vertexPos, vertexTextCoords));
                posBuilder.add(vertexPos);
                texBuilder.add(vertexTextCoords);
            }

            for (MD5Model.Triangle tri : mesh.getTriangles()) {
                indices.add(tri.getV1());
                indices.add(tri.getV2());
                indices.add(tri.getV3());

                // Normals
                WrappedVertex v0 = wrappedVertices.get(tri.getV1());
                WrappedVertex v1 = wrappedVertices.get(tri.getV2());
                WrappedVertex v2 = wrappedVertices.get(tri.getV3());
                Vector3f pos0 = v0.pos;
                Vector3f pos1 = v1.pos;
                Vector3f pos2 = v2.pos;

                // calculate triangle face normal as normal cross n2
                // add to vertex and normalize later
                Vector3f normal = (new Vector3f(pos2));
                normal.sub(pos0);
                Vector3f n2 = (new Vector3f(pos1));
                n2.sub(pos0);
                normal.cross(normal, n2);

                v0.norm.add(normal);
                v1.norm.add(normal);
                v2.norm.add(normal);
            }

            for(WrappedVertex wv : wrappedVertices) {
                wv.norm.normalize();
                normBuilder.add(wv.norm);
            }

            return new WrappedMesh(indices.build(), posBuilder.build(), normBuilder.build(), texBuilder.build(), mesh.getTexture());
        }

        private static String getLocation(String path)
        {
            if(path.endsWith(".png")) path = path.substring(0, path.length() - ".png".length());
            return path;
        }

        public Collection<ResourceLocation> getTextures() {
            ImmutableList.Builder<ResourceLocation> locationBuilder = ImmutableList.builder();
            for(MD5Model.Mesh mesh : model.getMeshes()) {
                String key = mesh.getTexture();
                locationBuilder.add(new ResourceLocation(modelLocation.getResourceDomain(), key));
            }
            return locationBuilder.build();
        }

        @Override
        public IBakedModel bake(IModelState state, VertexFormat format, Function<ResourceLocation, TextureAtlasSprite> bakedTextureGetter)
        {
            ImmutableMap.Builder<String, TextureAtlasSprite> builder = ImmutableMap.builder();
            TextureAtlasSprite missing = bakedTextureGetter.apply(new ResourceLocation("missingno"));
            for(Map.Entry<String, ResourceLocation> e : textures.entrySet())
            {
                if(e.getValue().getResourcePath().startsWith("#"))
                {
                    FMLLog.log.fatal("unresolved texture '{}' for b3d model '{}'", e.getValue().getResourcePath(), modelLocation);
                    builder.put(e.getKey(), missing);
                }
                else
                {
                    Mcmd5.logger.log(Level.INFO, "adding sprite with path " + e.getValue().getResourcePath());
                    if(bakedTextureGetter.apply(new ResourceLocation(e.getValue().getResourcePath())) == null)
                        Mcmd5.logger.log(Level.INFO, "sprite not found, oopsie");
                    builder.put(e.getKey(), bakedTextureGetter.apply(new ResourceLocation(e.getValue().getResourcePath())));
                }
            }
            builder.put("missingno", missing);
            return new BakedWrapper(meshes, model.getJoints(), state, smooth, gui3d, format, builder.build());
        }

        public IModelState getDefaultState() {
            return TRSRTransformation.identity();
        }

        public Optional<? extends IClip> getClip(String name) {
            return Optional.empty();
        }

        public IModel process(ImmutableMap<String, String> customData) {
            return this;
        }

        public IModel smoothLighting(boolean value) {
            return this;
        }

        public IModel gui3d(boolean value) {
            return this;
        }

        public IModel uvlock(boolean value) {
            return this;
        }

        public IModel retexture(ImmutableMap<String, String> textures) {
            return this;
        }
    }

    private static final class WrappedMesh {
        private final String texture;
        private final ImmutableList<Integer> indices;
        private final ImmutableList<Vector3f> positions;
        private final ImmutableList<Vector3f> normals;
        private final ImmutableList<Vector2f> texCoords;

        public WrappedMesh(ImmutableList<Integer> indices, ImmutableList<Vector3f> positions,
                           ImmutableList<Vector3f> normals, ImmutableList<Vector2f> texCoords,
                           String texture) {
            this.indices = indices;
            this.positions = positions;
            this.normals = normals;
            this.texCoords = texCoords;
            this.texture = texture;
        }

        public Vector3f getFaceNormal(int index) {
            Vector3f v1 = positions.get(index);
            Vector3f v2 = positions.get(index + 1);
            Vector3f v3 = positions.get(index + 2);

            Vector3f normal = new Vector3f(v3.x, v3.y, v3.z);
            Vector3f n1 = new Vector3f(v2.x, v2.y, v2.z);
            normal.sub(v1);
            n1.sub(v1);
            normal.cross(normal, n1);
            return normal;
        }
    }

    private static final class WrappedVertex {
        private final Vector3f pos;
        private Vector3f norm;
        private final Vector2f texCoords;

        public WrappedVertex(Vector3f pos, Vector2f texCoords) {
            this.pos = pos;
            this.texCoords = texCoords;
        }

        public void setNorm(Vector3f norm) {
            this.norm = norm;
        }

        public Vector3f getNorm() { return this.norm; }
    }

    private static final class  BakedWrapper implements IBakedModel {
        private final ImmutableList<WrappedMesh> meshes;
        private final IModelState state;
        private final boolean smooth;
        private final boolean gui3d;
        private final VertexFormat format;
        private final ImmutableMap<String, TextureAtlasSprite> textures;

        // this should be the default state (animation/pose) for this model
        // not sure when this is first created
        private ImmutableList<BakedQuad> quads;

        public BakedWrapper(ImmutableList<WrappedMesh> meshes, ImmutableList<MD5Model.Joint> joints, IModelState state,
                            boolean smooth, boolean gui3d, VertexFormat format, ImmutableMap<String, TextureAtlasSprite> textures) {
            this.meshes = meshes;
            this.state = state;
            this.smooth = smooth;
            this.gui3d = gui3d;
            this.format = format;
            this.textures = textures;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand)
        {
            if(side != null) return ImmutableList.of();
            IModelState modelState = this.state;
            if(state instanceof IExtendedBlockState)
            {
                IExtendedBlockState exState = (IExtendedBlockState)state;
                if(exState.getUnlistedNames().contains(Properties.AnimationProperty))
                {
                    // FIXME: should animation state handle the parent state, or should it remain here?
                    IModelState parent = this.state;
                    if(parent instanceof MD5State)
                    {
                        MD5State ps = (MD5State) parent;
                        parent = ps.getParent();
                    }
                    IModelState newState = exState.getValue(Properties.AnimationProperty);
                    if(newState != null)
                    {
                        if (parent == null)
                        {
                            modelState = newState;
                        }
                        else
                        {
                            modelState = new ModelStateComposition(parent, newState);
                        }
                    }
                }
            }
            if(quads == null)
            {
                ImmutableList.Builder<BakedQuad> builder = ImmutableList.builder();
                generateQuads(builder, meshes, this.state, ImmutableList.of());
                quads = builder.build();
            }
            // TODO: caching?
            if(this.state != modelState)
            {
                ImmutableList.Builder<BakedQuad> builder = ImmutableList.builder();
                generateQuads(builder, meshes, modelState, ImmutableList.of());
                return builder.build();
            }
            return quads;
        }

        private void generateQuads(ImmutableList.Builder<BakedQuad> builder, ImmutableList<WrappedMesh> meshes, final IModelState state, ImmutableList<String> path)
        {
            for(WrappedMesh mesh : meshes) {
                    /*Collection<MD5Model.Triangle> triangles = mesh.bake(new Function<MD5Model.Node, Matrix4f>()
                    {
                        private final TRSRTransformation global = state.apply(Optional.empty()).orElse(TRSRTransformation.identity());
                        private final LoadingCache<MD5Model.Node, TRSRTransformation> localCache = CacheBuilder.newBuilder()
                                .maximumSize(32)
                                .build(new CacheLoader<MD5Model.Node, TRSRTransformation>()
                                {
                                    @Override
                                    public TRSRTransformation load(MD5Model.Node node) throws Exception
                                    {
                                        return state.apply(Optional.of(new MD5Joint(node))).orElse(TRSRTransformation.identity());
                                    }
                                });

                        @Override
                        public Matrix4f apply(MD5Model.Node node)
                        {
                            return global.compose(localCache.getUnchecked(node)).getMatrix();
                        }
                    });*/

                    for(int i = 0 ; i < mesh.indices.size() - 2; i += 3) {
                        UnpackedBakedQuad.Builder quadBuilder = new UnpackedBakedQuad.Builder(format);
                        quadBuilder.setContractUVs(true);
                        Vector3f faceNormal = mesh.getFaceNormal(i);
                        quadBuilder.setQuadOrientation(EnumFacing.getFacingFromVector(faceNormal.x, faceNormal.y, faceNormal.z));
                        // rename this shit because they don't correspond to axes but instead to
                        // the vertices of a triangle

                        int xind = mesh.indices.get(i);
                        int yind = mesh.indices.get(i + 1);
                        int zind = mesh.indices.get(i +2);
                        TextureAtlasSprite sprite = this.textures.get(mesh.texture);
                        quadBuilder.setTexture(sprite);

                        putVertexData(quadBuilder, mesh.positions.get(xind), mesh.normals.get(xind), mesh.texCoords.get(xind), sprite);
                        putVertexData(quadBuilder, mesh.positions.get(yind), mesh.normals.get(yind), mesh.texCoords.get(yind), sprite);
                        putVertexData(quadBuilder, mesh.positions.get(zind), mesh.normals.get(zind), mesh.texCoords.get(zind), sprite);
                        // the following code assumes one textures per instance_geometry entry.
                        // need to study spec further to determine if this is always the case.
                        //
                        //texture must be a ResourceLocation

                    }
            }
        }

        private final void putVertexData(UnpackedBakedQuad.Builder builder, Vector3f pos, Vector3f faceNormal, Vector2f texCoords, TextureAtlasSprite sprite)
        {
            // TODO handle everything not handled (texture transformations, bones, transformations, normals, e.t.c)

            for(int e = 0; e < format.getElementCount(); e++)
            {
                switch(format.getElement(e).getUsage())
                {
                    case POSITION:
                        builder.put(e, pos.x, pos.y, pos.z, 1);
                        break;
                    /*case COLOR:
                        if(v.getColor() != null)
                        {
                            builder.put(e, v.getColor().x, v.getColor().y, v.getColor().z, v.getColor().w);
                        }
                        else
                        {
                            builder.put(e, 1, 1, 1, 1);
                        }
                        break;*/
                    case UV:
                        // TODO handle more brushes
                            builder.put(e,
                                    sprite.getInterpolatedU(texCoords.x * 16),
                                    sprite.getInterpolatedV(texCoords.y * 16),
                                    0,
                                    1
                            );
                        break;
                    case NORMAL:
                        builder.put(e, faceNormal.x, faceNormal.y, faceNormal.z, 0);
                        break;
                    default:
                        builder.put(e);
                }
            }
        }

        @Override
        public boolean isAmbientOcclusion()
        {
            return smooth;
        }

        @Override
        public boolean isGui3d()
        {
            return gui3d;
        }

        @Override
        public boolean isBuiltInRenderer()
        {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleTexture()
        {
            // FIXME somehow specify particle texture in the model
            return textures.values().asList().get(0);
        }

        @Override
        public Pair<? extends IBakedModel, Matrix4f> handlePerspective(ItemCameraTransforms.TransformType cameraTransformType)
        {
            return PerspectiveMapWrapper.handlePerspective(this, state, cameraTransformType);
        }

        @Override
        public ItemOverrideList getOverrides()
        {
            // TODO handle items
            return ItemOverrideList.NONE;
        }
    }

    // use for nodes defined in library_nodes; not sure if animation is hierarchical or
    // also local transforms
    static final class MD5Joint implements IJoint {
        public TRSRTransformation getInvBindPose() {
            return new TRSRTransformation(new Matrix4f());
        }

        public Optional<? extends IJoint> getParent() {
            return Optional.empty();
        }
    }
}

