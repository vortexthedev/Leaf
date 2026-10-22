package ru.minecomplex.mac.world;

import ru.minecomplex.mac.collision.Aabb;
import ru.minecomplex.mac.collision.VoxelShape;
import ru.minecomplex.mac.util.math.VanillaMath;
import ru.minecomplex.mac.util.Tri;

/** Forkable native 26.3 client border extent and uncertainty of its tick phase. */
public final class ClientWorldBorder {
    private double centerX, centerZ, from=5.9999968E7, to=5.9999968E7, size=5.9999968E7, previous=5.9999968E7;
    private int absoluteMax=29999984;
    private long duration, remaining;
    private boolean moving;
    private int ahead;
    public ClientWorldBorder() { }
    private ClientWorldBorder(ClientWorldBorder b) {
        centerX=b.centerX;centerZ=b.centerZ;from=b.from;to=b.to;size=b.size;previous=b.previous;
        absoluteMax=b.absoluteMax;duration=b.duration;remaining=b.remaining;moving=b.moving;ahead=b.ahead;
    }
    public ClientWorldBorder copy() { return new ClientWorldBorder(this); }
    public void setCenter(double x,double z) { centerX=x;centerZ=z; }
    public void setAbsoluteMaxSize(int value) { absoluteMax=value; }
    public void setSize(double value) { moving=false;ahead=0;from=to=size=previous=value; }
    /** Duration is measured in client border ticks. */
    public void lerp(double from,double to,long duration) {
        this.from=from;this.to=to;this.duration=duration;remaining=duration;
        moving=from!=to;ahead=0;size=previous=moving?calculateTickSize():to;
    }
    private double calculateTickSize() {
        double progress=((double)duration-remaining)/(double)duration;
        return progress<1.0?VanillaMath.lerp(progress,from,to):to;
    }
    public void ahead(int ticks) { ahead=moving?ticks:0; }
    public double size() { return size; }
    public void tick() {
        if(!moving)return;
        remaining--;previous=size;size=calculateTickSize();if(remaining<=0)setSize(to);
    }
    public Snapshot snapshot() { return snapshotOf(moving?VanillaMath.lerp(0.0F,previous,size):size); }
    private double[] possibleSizes() {
        double estimate=moving?VanillaMath.lerp(0.0F,previous,size):size;
        double other=estimate;
        if(moving&&ahead>0) {
            var later=copy();
            for(int i=0;i<ahead&&later.moving;i++)later.tick();
            other=later.moving?VanillaMath.lerp(0.0F,later.previous,later.size):later.size;
        }
        return new double[]{Math.min(estimate,other),Math.max(estimate,other)};
    }
    /** Whether (x, z) is within the bounds widened by {@code margin} in every phase the client may be in. */
    public Tri within(double x,double z,double margin) {
        double[] sizes=possibleSizes();
        boolean small=snapshotOf(sizes[0]).within(x,z,margin), large=snapshotOf(sizes[1]).within(x,z,margin);
        // a larger border contains a smaller one's inside
        return small==large?(small?Tri.TRUE:Tri.FALSE):Tri.EITHER;
    }
    /**
     * {@code collectColliders}' border for an entity at (x, z): {@code getCollisionShape()} when {@code isInsideCloseToBorder},
     * else {@code null}; {@code known} is false when the client's possible phases disagree (then the shape is this
     * model's estimate).
     */
    public Collider collision(double x,double z,Aabb query) {
        double[] sizes=possibleSizes();
        Snapshot estimate=snapshot();
        if(sizes[0]==sizes[1]) return new Collider(estimate.collision(x,z,query),true);
        Snapshot small=snapshotOf(sizes[0]), large=snapshotOf(sizes[1]);
        // the distance to the border grows with its size, and so does the widened inside: close holds on an interval of
        // sizes, and the rounded interior of the shape moves monotonically with the size
        if(small.close(x,z,query)&&large.close(x,z,query)&&small.sameShape(large)) return new Collider(small.shape(),true);
        double reach=Snapshot.closeReach(query);
        if(!(small.distance(x,z)<reach*2.0 && large.within(x,z,reach))) return new Collider(null,true);
        return new Collider(estimate.collision(x,z,query),false);
    }
    /** A border collider ({@code null}: none) and whether every phase the client may be in gives it. */
    public record Collider(VoxelShape shape, boolean known) { }
    private Snapshot snapshotOf(double boundsSize) {
        return new Snapshot(VanillaMath.clamp(centerX-boundsSize/2.0,-absoluteMax,absoluteMax),
                VanillaMath.clamp(centerZ-boundsSize/2.0,-absoluteMax,absoluteMax),
                VanillaMath.clamp(centerX+boundsSize/2.0,-absoluteMax,absoluteMax),
                VanillaMath.clamp(centerZ+boundsSize/2.0,-absoluteMax,absoluteMax));
    }
    /** Evaluated once for an immutable movement environment; no wall-clock reads during simulation. */
    public record Snapshot(double minX,double minZ,double maxX,double maxZ) {
        public double distance(double x,double z) {
            double result=Math.min(x-minX,maxX-x);
            result=Math.min(result,z-minZ);
            return Math.min(result,maxZ-z);
        }
        public boolean within(double x,double z,double margin) {
            return x>=minX-margin && x<maxX+margin && z>=minZ-margin && z<maxZ+margin;
        }
        public boolean close(double x,double z,Aabb box) {
            double max=closeReach(box);
            return distance(x,z)<max*2.0 && within(x,z,max);
        }
        /** {@code isInsideCloseToBorder}'s {@code max(absMax(xsize, zsize), 1)}. */
        static double closeReach(Aabb box) {
            return Math.max(Math.max(Math.abs(box.maxX()-box.minX()),Math.abs(box.maxZ()-box.minZ())),1.0);
        }
        /** Whether {@link #shape} of both is the same (the same rounded interior). */
        boolean sameShape(Snapshot other) {
            return Math.floor(minX)==Math.floor(other.minX)&&Math.ceil(maxX)==Math.ceil(other.maxX)
                    &&Math.floor(minZ)==Math.floor(other.minZ)&&Math.ceil(maxZ)==Math.ceil(other.maxZ);
        }
        public VoxelShape collision(double x,double z,Aabb query) { return close(x,z,query)?shape():null; }
        /** Shapes.join(INFINITY, rounded interior box, ONLY_FIRST). The interior voxel is the only hole. */
        public VoxelShape shape() {
            double x0=Math.floor(minX),x1=Math.ceil(maxX),z0=Math.floor(minZ),z1=Math.ceil(maxZ);
            if(x1-x0<1.0E-7 || z1-z0<1.0E-7) return VoxelShape.array(1,1,1,new long[]{1},
                    new double[]{Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY},
                    new double[]{Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY},
                    new double[]{Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY});
            return VoxelShape.array(3,1,3,new long[]{0x1ef},
                    new double[]{Double.NEGATIVE_INFINITY,x0,x1,Double.POSITIVE_INFINITY},
                    new double[]{Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY},
                    new double[]{Double.NEGATIVE_INFINITY,z0,z1,Double.POSITIVE_INFINITY});
        }
    }
}
