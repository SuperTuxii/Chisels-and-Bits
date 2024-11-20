package mod.chiselsandbits.client.model.meshing;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import mod.chiselsandbits.api.blockinformation.IBlockInformation;
import mod.chiselsandbits.api.multistate.StateEntrySize;
import mod.chiselsandbits.client.model.baked.chiseled.ChiselRenderType;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class GreedyMeshBuilder {

    private GreedyMeshBuilder() {
        throw new IllegalStateException("Cannot instantiate utility class");
    }

    @FunctionalInterface
    public interface MaterialProvider {
        IBlockInformation getMaterial(int x, int y, int z);
    }

    public static GreedyMeshFace[] buildMesh(MaterialProvider materialData, ChiselRenderType chiselRenderType) {
        final List<GreedyMeshFace> faces = new ArrayList<>();
        final Object2IntMap<IBlockInformation> indexByMaterial = new Object2IntOpenHashMap<>();
        final ArrayList<IBlockInformation> materials = new ArrayList<>();

        class MaterialProcessor {
            final int getMaterialIndex(int x, int y, int z) {
                final IBlockInformation blockInformation = materialData.getMaterial(x, y, z);

                if (!chiselRenderType.isRequiredForRendering(blockInformation))
                    return 0;

                return indexByMaterial.computeIfAbsent(blockInformation, k -> {
                    int index = indexByMaterial.size() + 1;
                    materials.add(blockInformation);
                    return index;
                });
            }
        }
        final MaterialProcessor materialProcessor = new MaterialProcessor();
        
        final boolean renderTypeSolid = chiselRenderType.layer.equals(RenderType.solid());
        final int bitsPerBlockSide = StateEntrySize.current().getBitsPerBlockSide();
        final int bitsPerBlockSideP = bitsPerBlockSide + 2;
        final int bitsPerBlockSideP2 = bitsPerBlockSideP * bitsPerBlockSideP;

        final ArrayList<int[]> materialMaskCols = new ArrayList<>();
        materialMaskCols.add(0, new int[3 * bitsPerBlockSideP2]);

        for (int x = 0; x < bitsPerBlockSideP; x++) {
            for (int y = 0; y < bitsPerBlockSideP; y++) {
                for (int z = 0; z < bitsPerBlockSideP; z++) {
                    if (!materialData.getMaterial(x - 1, y - 1, z - 1).isSeeThrough()) {
                        materialMaskCols.get(0)[x + (z * bitsPerBlockSideP)] |= (1 << y);
                        materialMaskCols.get(0)[x + (y * bitsPerBlockSideP) + bitsPerBlockSideP2] |= (1 << z);
                        materialMaskCols.get(0)[y + (z * bitsPerBlockSideP) + (bitsPerBlockSideP2 * 2)] |= (1 << x);
                    }
                    if (!renderTypeSolid) {
                        int materialIndex = materialProcessor.getMaterialIndex(x - 1, y - 1, z - 1);
                        if (materialIndex != 0) {
                            while (materialIndex >= materialMaskCols.size())
                                materialMaskCols.add(new int[3 * bitsPerBlockSideP2]);
                            materialMaskCols.get(materialIndex)[x + (z * bitsPerBlockSideP)] |= (1 << y);
                            materialMaskCols.get(materialIndex)[x + (y * bitsPerBlockSideP) + bitsPerBlockSideP2] |= (1 << z);
                            materialMaskCols.get(materialIndex)[y + (z * bitsPerBlockSideP) + (bitsPerBlockSideP2 * 2)] |= (1 << x);
                        }
                    }
                }
            }
        }

        List<HashMap<Integer, HashMap<Integer, int[]>>> faceData = new ArrayList<>(6);

        if (renderTypeSolid) {
            int[] faceMaskCols = new int[6 * bitsPerBlockSideP2];

            for (int axis = 0; axis < 3; axis++) {
                for (int i = 0; i < bitsPerBlockSideP2; i++) {
                    int col = materialMaskCols.get(0)[i + (bitsPerBlockSideP2 * axis)];
                    faceMaskCols[i + (bitsPerBlockSideP2 * axis * 2)] = col & ~(col << 1);
                    faceMaskCols[i + (bitsPerBlockSideP2 * ((axis * 2) + 1))] = col & ~(col >> 1);
                }
            }

            for (int axis = 0; axis < 6; axis++) {
                faceData.add(new HashMap<>());
                for (int x = 0; x < bitsPerBlockSide; x++) {
                    for (int z = 0; z < bitsPerBlockSide; z++) {
                        int colIndex = 1 + x + ((z + 1) * bitsPerBlockSideP) + bitsPerBlockSideP2 * axis;

                        int col = faceMaskCols[colIndex] >> 1;
                        col &= ~(1 << bitsPerBlockSide);

                        while (col != 0) {
                            int y = Integer.numberOfTrailingZeros(col);
                            col &= col - 1;

                            int bitMaterial = materialProcessor.getMaterialIndex(
                                    Direction.values()[axis].getAxis().choose(y, x, x),
                                    Direction.values()[axis].getAxis().choose(x, y, z),
                                    Direction.values()[axis].getAxis().choose(z, z, y)
                            );

                            faceData.get(axis)
                                    .computeIfAbsent(bitMaterial, k -> new HashMap<>())
                                    .computeIfAbsent(y, k -> new int[bitsPerBlockSide])[x] |= 1 << z;
                        }
                    }
                }
            }
        }else {
            final ArrayList<int[]> faceColMasks = new ArrayList<>();
            materialMaskCols.forEach((material) -> faceColMasks.add(new int[6 * bitsPerBlockSideP2]));

            for (int axis = 0; axis < 3; axis++) {
                for (int i = 0; i < bitsPerBlockSideP2; i++) {
                    int col = materialMaskCols.get(0)[i + (bitsPerBlockSideP2 * axis)];
                    for (int material = 1; material < faceColMasks.size(); material++) {
                        int materialCol = materialMaskCols.get(material)[i + (bitsPerBlockSideP2 * axis)];
                        faceColMasks.get(material)[i + (bitsPerBlockSideP2 * axis * 2)] = materialCol & ~((materialCol | col) << 1);
                        faceColMasks.get(material)[i + (bitsPerBlockSideP2 * ((axis * 2) + 1))] = materialCol & ~((materialCol | col) >> 1);
                    }
                }
            }

            for (int axis = 0; axis < 6; axis++) {
                faceData.add(new HashMap<>());
                for (int x = 0; x < bitsPerBlockSide; x++) {
                    for (int z = 0; z < bitsPerBlockSide; z++) {
                        int colIndex = 1 + x + ((z + 1) * bitsPerBlockSideP) + bitsPerBlockSideP2 * axis;
                        for (int material = 1; material < faceColMasks.size(); material++) {
                            int col = faceColMasks.get(material)[colIndex] >> 1;
                            col &= ~(1 << bitsPerBlockSide);

                            while (col != 0) {
                                int y = Integer.numberOfTrailingZeros(col);
                                col &= col - 1;

                                faceData.get(axis)
                                        .computeIfAbsent(material, k -> new HashMap<>())
                                        .computeIfAbsent(y, k -> new int[bitsPerBlockSide])[x] |= 1 << z;
                            }
                        }
                    }
                }
            }
        }

        for (int axis = 0; axis < faceData.size(); axis++) {
            Direction faceDirection = Direction.values()[axis];
            faceData.get(axis).forEach((material, axisFaces) -> axisFaces.forEach(
                    (axisPos, plane) -> faces.addAll(greedyMeshBinaryPlane(plane, materials.get(material - 1), faceDirection, axisPos, bitsPerBlockSide))
            ));
        }
        return faces.toArray(new GreedyMeshFace[0]);
    }

    private static List<GreedyMeshFace> greedyMeshBinaryPlane(int[] data, IBlockInformation material, Direction direction, int axisPos, final int BITS_PER_BLOCK_SIDE) {
        List<GreedyMeshFace> axisFaces = new ArrayList<>();
        for (int row = 0; row < data.length; row++) {
            int y = 0;
            while (y < BITS_PER_BLOCK_SIDE) {
                y += Integer.numberOfTrailingZeros(data[row] >> y);
                if (y >= BITS_PER_BLOCK_SIDE) continue;
                int height = Integer.numberOfTrailingZeros(~data[row] >> y);

                int hAsMask = height < 32 ? (1 << height) - 1 : ~0;
                int mask = hAsMask << y;

                int width = 1;
                while (row + width < BITS_PER_BLOCK_SIDE) {
                    int nextRowH = (data[row + width] >> y) & hAsMask;
                    if (nextRowH != hAsMask) break;
                    data[row + width] = data[row + width] & ~mask;
                    width++;
                }
                axisFaces.add(generateFace(material, row, y, height, width, axisPos, direction, BITS_PER_BLOCK_SIDE));
                y += height;
            }
        }
        return axisFaces;
    }

    @NotNull
    private static GreedyMeshFace generateFace(IBlockInformation material, int x, int y, int height, int width, int axisPos, Direction direction, final int BITS_PER_BLOCK_SIDE) {
        final Vector3f lowerLeft = transformPos(new Vector3f(x, axisPos, y), direction).div(BITS_PER_BLOCK_SIDE);
        final Vector3f upperLeft = transformPos(new Vector3f(x, axisPos, y + height), direction).div(BITS_PER_BLOCK_SIDE);
        final Vector3f lowerRight = transformPos(new Vector3f(x + width, axisPos, y), direction).div(BITS_PER_BLOCK_SIDE);
        final Vector3f upperRight = transformPos(new Vector3f(x + width, axisPos, y + height), direction).div(BITS_PER_BLOCK_SIDE);

        final boolean isEdge = axisPos == 0 || axisPos == BITS_PER_BLOCK_SIDE;

        return new GreedyMeshFace(material, lowerLeft, upperLeft, lowerRight, upperRight, direction, isEdge);
    }

    private static Vector3f transformPos(Vector3f vector, Direction direction) {
        Vector3f returnVector;
        if (direction.getAxis().equals(Direction.Axis.X)) {
            returnVector = new Vector3f(vector.y, vector.x, vector.z);
        }else if (direction.getAxis().equals(Direction.Axis.Y)) {
            returnVector = vector;
        }else {
            returnVector = new Vector3f(vector.x, vector.z, vector.y);
        }
        if (direction.getAxisDirection().equals(Direction.AxisDirection.POSITIVE))
            returnVector.add(direction.getNormal().getX(), direction.getNormal().getY(), direction.getNormal().getZ());
        return returnVector;
    }
}
