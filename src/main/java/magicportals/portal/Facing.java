package magicportals.portal;

import java.util.Locale;
import org.bukkit.block.structure.StructureRotation;

/**
 * The direction a portal's opening faces (towards the players standing in front of it).
 * Every portal has a local frame: z points towards the viewer side, x runs along the opening, y is up.
 */
public enum Facing {
    //     index  X axis    Z axis     yaw   world→local                          local→world
    SOUTH(0, 1, 0, 0, 1, 0f, StructureRotation.NONE, StructureRotation.NONE),
    WEST(1, 0, 1, -1, 0, 90f, StructureRotation.COUNTERCLOCKWISE_90, StructureRotation.CLOCKWISE_90),
    NORTH(2, -1, 0, 0, -1, 180f, StructureRotation.CLOCKWISE_180, StructureRotation.CLOCKWISE_180),
    EAST(3, 0, -1, 1, 0, -90f, StructureRotation.CLOCKWISE_90, StructureRotation.COUNTERCLOCKWISE_90);

    public final int index;
    /** World components of the local x axis (along the opening) and z axis (towards the viewer). */
    public final int xx, xz, zx, zz;
    /** Yaw of a player looking away from the portal, i.e. in the facing direction. */
    public final float yaw;
    final StructureRotation worldToLocal, localToWorld;

    Facing(int index, int xx, int xz, int zx, int zz, float yaw, StructureRotation worldToLocal, StructureRotation localToWorld) {
        this.index = index; this.xx = xx; this.xz = xz; this.zx = zx; this.zz = zz; this.yaw = yaw;
        this.worldToLocal = worldToLocal; this.localToWorld = localToWorld;
    }

    /** Rotation for snapshots shown on the other side: into this portal's frame, then turned by 180°
     *  because the viewer over there looks through the portal from the opposite direction. */
    StructureRotation captureRotation() {
        return switch (this) {
            case SOUTH -> StructureRotation.CLOCKWISE_180;
            case NORTH -> StructureRotation.NONE;
            case EAST -> StructureRotation.COUNTERCLOCKWISE_90;
            case WEST -> StructureRotation.CLOCKWISE_90;
        };
    }

    public static Facing parse(String text) {
        try { return valueOf(text.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException e) { return SOUTH; }
    }

    /** The facing whose opening points towards the given horizontal direction (dx, dz). */
    public static Facing towards(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? EAST : WEST;
        return dz > 0 ? SOUTH : NORTH;
    }
}
