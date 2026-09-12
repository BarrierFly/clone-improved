package dev.cloneimproved.transform;

/**
 * Pure coordinate math for the transform chain (design doc §4.1/§4.2).
 *
 * <p>A "placement" is described by two state values: the anchor (the region's lower-NW corner,
 * north = −Z) and the dims (sizeX, sizeY, sizeZ). Ops are applied in command order:
 * {@code move} re-anchors the placement onto the command's {@code <destination>},
 * {@code rotate} pivots around the current anchor, {@code mirror} reflects the whole region
 * through the given vertical plane.
 *
 * <p>Rotation formulas (local coords {@code dx ∈ [0, sx)}, {@code dz ∈ [0, sz)}):
 * <ul>
 *   <li>cw: {@code dx' = sz−1−dz, dz' = dx} — equals vanilla {@code (x,z) → (−z,x)} normalised
 *       back into the positive quadrant, so vanilla {@code Rotation.CLOCKWISE_90} matches.</li>
 *   <li>ccw: {@code dx' = dz, dz' = sx−1−dx}</li>
 *   <li>reverse: {@code dx' = sx−1−dx, dz' = sz−1−dz}</li>
 * </ul>
 *
 * <p>Mirror world formula: {@code x' = 2c − x} (plane perpendicular to X) and
 * {@code z' = 2c − z} (plane perpendicular to Z). Locally that is simply
 * {@code dx' = sx−1−dx} / {@code dz' = sz−1−dz}; the anchor moves to
 * {@code 2c − maxX}. The plane constant {@code c} never affects local mapping, only the
 * anchor — which is why pre-{@code move} mirrors flip content while their anchor shift is
 * afterwards replaced by {@code move}'s re-anchoring.
 *
 * <p>This class is Minecraft-free and is the main unit-test target.
 */
public final class PlacementMapper {
    private PlacementMapper() {
    }

    /** A region placement: anchor = lower-NW corner (min x/y/z), sizes strictly positive. */
    public record Placement(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        public Placement {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("Empty placement: " + sizeX + "x" + sizeY + "x" + sizeZ);
            }
        }

        public int maxX() {
            return minX + sizeX - 1;
        }

        public int maxY() {
            return minY + sizeY - 1;
        }

        public int maxZ() {
            return minZ + sizeZ - 1;
        }

        public long volume() {
            return (long) sizeX * sizeY * sizeZ;
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX() && y >= minY && y <= maxY() && z >= minZ && z <= maxZ();
        }

        public Placement withAnchor(int x, int y, int z) {
            return new Placement(x, y, z, sizeX, sizeY, sizeZ);
        }
    }

    /** Applies a single rotate/mirror op to a placement (anchor + dims only). Move is handled by the walk. */
    public static Placement apply(Placement p, TransformOp op) {
        if (op instanceof TransformOp.Rotate rotate) {
            return switch (rotate.dir()) {
                case CW, CCW -> new Placement(p.minX, p.minY, p.minZ, p.sizeZ, p.sizeY, p.sizeX);
                case REVERSE -> p;
            };
        }
        if (op instanceof TransformOp.Mirror mirror) {
            // anchor' = 2c − oldMax on the mirrored axis; sizes unchanged
            if (mirror.xAxis()) {
                return new Placement(times2Rounded(mirror.coord()) - p.maxX(), p.minY, p.minZ, p.sizeX, p.sizeY, p.sizeZ);
            }
            return new Placement(p.minX, p.minY, times2Rounded(mirror.coord()) - p.maxZ(), p.sizeX, p.sizeY, p.sizeZ);
        }
        throw new IllegalArgumentException("Move is re-anchored by the walk, not apply(): " + op);
    }

    /** The integer value of {@code 2c}; callers must validate {@code 2c ∈ ℤ} first. */
    private static int times2Rounded(double coord) {
        return (int) Math.rint(coord * 2);
    }

    /** One step of the walk: current placement + local offset of the tracked block. */
    private record Walk(Placement placement, int dx, int dy, int dz, boolean moved) {
    }

    private static Walk step(Walk w, TransformOp op, int destX, int destY, int destZ) {
        int dx = w.dx;
        int dz = w.dz;
        Placement cur = w.placement;
        if (op instanceof TransformOp.Move) {
            return new Walk(cur.withAnchor(destX, destY, destZ), dx, w.dy, dz, true);
        }
        int sx = cur.sizeX;
        int sz = cur.sizeZ;
        if (op instanceof TransformOp.Rotate rotate) {
            switch (rotate.dir()) {
                case CW -> {
                    int ndx = sz - 1 - dz;
                    int ndz = dx;
                    dx = ndx;
                    dz = ndz;
                }
                case CCW -> {
                    int ndx = dz;
                    int ndz = sx - 1 - dx;
                    dx = ndx;
                    dz = ndz;
                }
                case REVERSE -> {
                    dx = sx - 1 - dx;
                    dz = sz - 1 - dz;
                }
            }
        } else if (op instanceof TransformOp.Mirror mirror) {
            if (mirror.xAxis()) {
                dx = sx - 1 - dx;
            } else {
                dz = sz - 1 - dz;
            }
        }
        return new Walk(apply(cur, op), dx, w.dy, dz, w.moved);
    }

    private static Walk walk(TransformSpec spec, Placement source, int destX, int destY, int destZ, int x, int y, int z) {
        Walk w = new Walk(source, x - source.minX, y - source.minY, z - source.minZ, false);
        for (TransformOp op : spec.ops()) {
            w = step(w, op, destX, destY, destZ);
        }
        // Chains without move (mask-only / vanilla modes) translate implicitly to the destination,
        // matching vanilla's "destination + source dims" semantics.
        if (!w.moved) {
            w = new Walk(w.placement.withAnchor(destX, destY, destZ), w.dx, w.dy, w.dz, true);
        }
        return w;
    }

    /**
     * Walks the chain and returns the final placement. {@code move} re-anchors onto
     * {@code (destX, destY, destZ)}.
     */
    public static Placement finalPlacement(TransformSpec spec, Placement source, int destX, int destY, int destZ) {
        return walk(spec, source, destX, destY, destZ, source.minX, source.minY, source.minZ).placement();
    }

    /**
     * Maps a source-region block to its destination-region block.
     *
     * @return absolute destination coordinates {@code {x, y, z}}
     */
    public static int[] mapToDest(TransformSpec spec, Placement source, int destX, int destY, int destZ, int x, int y, int z) {
        Walk w = walk(spec, source, destX, destY, destZ, x, y, z);
        return new int[] {w.placement().minX() + w.dx(), w.placement().minY() + w.dy(), w.placement().minZ() + w.dz()};
    }
}
