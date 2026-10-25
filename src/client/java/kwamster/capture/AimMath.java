package kwamster.capture;

import kwamster.core.model.AimSample;
import kwamster.core.model.AimZone;
import kwamster.core.model.Pos;
import kwamster.core.stats.AimZones;

/**
 * Aim placement geometry (S3, §5.3): where on the target's hitbox a swing went, as normalised
 * offsets {@code (h, v)} plus an {@link AimZone} for landed hits. Doubles only; Minecraft-free (R6).
 *
 * <p>Points and directions are {@link Pos} values (blocks); the hitbox is a {@link Box}. Every result
 * is finite: degenerate input (a zero-size box, a zero look vector, NaN) yields a centred offset
 * rather than NaN or infinity.
 *
 * <ul>
 *   <li><b>Right axis.</b> The horizontal look {@code f = normalize(look.x, 0, look.z)}; when its length
 *       is below 1e-6 (looking straight up or down) it comes from the yaw instead,
 *       {@code f = (-sin yaw, 0, cos yaw)}. Then {@code right = (-f.z, 0, f.x)}.</li>
 *   <li><b>Offsets.</b> {@code h = ((P - C)·right) / hw} with the projected half-width
 *       {@code hw = (xsize/2)·|right.x| + (zsize/2)·|right.z|}, and {@code v = (P.y - C.y) / (H/2)}.</li>
 *   <li><b>Eye inside the box</b> ({@code box.inflate(1e-4).contains(eye)}): {@code h = v = 0},
 *       {@code eyeInside = true}, zone CENTER.</li>
 *   <li><b>Zone</b> (landed only): {@link AimZones#of(double, double)}, precedence HIGH &gt; LOW &gt; EDGE &gt; CENTER.</li>
 * </ul>
 */
public final class AimMath {
	/** Below this length a direction counts as zero. */
	private static final double EPSILON = 1.0e-6;
	/** The eye-inside test inflates the box by this much (§5.3). */
	private static final double INSIDE_MARGIN = 1.0e-4;

	private AimMath() {
	}

	/**
	 * An axis-aligned box in blocks, mirroring Minecraft's {@code AABB}. A max below its min is
	 * treated as an empty extent on that axis.
	 */
	public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		/** The box centre. */
		public Pos centre() {
			return new Pos((this.minX + this.maxX) * 0.5, (this.minY + this.maxY) * 0.5, (this.minZ + this.maxZ) * 0.5);
		}

		public double xSize() {
			return this.maxX - this.minX;
		}

		public double ySize() {
			return this.maxY - this.minY;
		}

		public double zSize() {
			return this.maxZ - this.minZ;
		}

		/** {@code AABB.contains} semantics after inflating every side by {@code margin}: min inclusive, max exclusive. */
		public boolean containsInflated(Pos p, double margin) {
			return p.x() >= this.minX - margin && p.x() < this.maxX + margin
					&& p.y() >= this.minY - margin && p.y() < this.maxY + margin
					&& p.z() >= this.minZ - margin && p.z() < this.maxZ + margin;
		}
	}

	/**
	 * The aim sample for hit point {@code point} on {@code box}, seen from {@code eye} looking along
	 * {@code look} (any length) with yaw {@code yawDeg} (degrees, Minecraft convention: 0 = +Z).
	 * The zone is set only when {@code landed}.
	 */
	public static AimSample sample(Pos point, Box box, Pos eye, Pos look, float yawDeg, boolean landed) {
		if (box.containsInflated(eye, INSIDE_MARGIN)) {
			return new AimSample(0.0, 0.0, landed ? AimZone.CENTER : null, true);
		}
		Pos c = box.centre();
		double[] right = rightAxis(look, yawDeg);
		double hw = (box.xSize() * 0.5) * Math.abs(right[0]) + (box.zSize() * 0.5) * Math.abs(right[1]);
		double halfHeight = box.ySize() * 0.5;
		double across = (point.x() - c.x()) * right[0] + (point.z() - c.z()) * right[1];
		double h = hw > EPSILON ? finiteOrZero(across / hw) : 0.0;
		double v = halfHeight > EPSILON ? finiteOrZero((point.y() - c.y()) / halfHeight) : 0.0;
		return new AimSample(h, v, landed ? AimZones.of(h, v) : null, false);
	}

	/** {@link #sample} for a landed hit whose ray entered the box at {@code hit} (vanilla's {@code EntityHitResult.getLocation()}). */
	public static AimSample landed(Pos hit, Box box, Pos eye, Pos look, float yawDeg) {
		return sample(hit, box, eye, look, yawDeg, true);
	}

	/** {@link #sample} for a missed swing: the point on the look ray closest to the box centre (no zone). */
	public static AimSample missed(Box box, Pos eye, Pos look, float yawDeg) {
		return sample(closestPointToCentre(box, eye, look), box, eye, look, yawDeg, false);
	}

	/**
	 * The same offsets with the zone recomputed for the swing's final outcome: a zone (CENTER when the
	 * eye was inside) if {@code landed}, none otherwise.
	 */
	public static AimSample withOutcome(AimSample s, boolean landed) {
		if (!landed) {
			return s.zone() == null ? s : new AimSample(s.h(), s.v(), null, s.eyeInside());
		}
		AimZone zone = s.eyeInside() ? AimZone.CENTER : AimZones.of(s.h(), s.v());
		return zone == s.zone() ? s : new AimSample(s.h(), s.v(), zone, s.eyeInside());
	}

	/**
	 * The point on the forward look ray closest to the box centre:
	 * {@code eye + look·max(0, (C - eye)·look)} with {@code look} normalised (combat-tracker's
	 * {@code placementOnHitbox}). A zero look vector yields the eye itself.
	 */
	public static Pos closestPointToCentre(Box box, Pos eye, Pos look) {
		double len = Math.sqrt(look.x() * look.x() + look.y() * look.y() + look.z() * look.z());
		if (!(len > EPSILON) || !Double.isFinite(len)) {
			return eye;
		}
		double lx = look.x() / len;
		double ly = look.y() / len;
		double lz = look.z() / len;
		Pos c = box.centre();
		double along = Math.max(0.0, (c.x() - eye.x()) * lx + (c.y() - eye.y()) * ly + (c.z() - eye.z()) * lz);
		return new Pos(eye.x() + lx * along, eye.y() + ly * along, eye.z() + lz * along);
	}

	/**
	 * Distance from {@code eye} along the forward look ray to where it enters {@code box}
	 * (the {@code rayReach} of §4.2): 0 when the eye is inside the box ({@code AABB.contains}, as vanilla's
	 * entity pick treats it), NaN when the forward ray misses the box or the look vector is zero.
	 */
	public static double rayEntryDistance(Box box, Pos eye, Pos look) {
		if (box.containsInflated(eye, 0.0)) {
			return 0.0;
		}
		double len = Math.sqrt(look.x() * look.x() + look.y() * look.y() + look.z() * look.z());
		if (!(len > EPSILON) || !Double.isFinite(len)) {
			return Double.NaN;
		}
		double[] origin = {eye.x(), eye.y(), eye.z()};
		double[] dir = {look.x() / len, look.y() / len, look.z() / len};
		double[] min = {box.minX(), box.minY(), box.minZ()};
		double[] max = {box.maxX(), box.maxY(), box.maxZ()};
		double tNear = 0.0;
		double tFar = Double.POSITIVE_INFINITY;
		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(dir[axis]) < 1.0e-12) {
				if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
					return Double.NaN;
				}
				continue;
			}
			double t1 = (min[axis] - origin[axis]) / dir[axis];
			double t2 = (max[axis] - origin[axis]) / dir[axis];
			if (t1 > t2) {
				double swap = t1;
				t1 = t2;
				t2 = swap;
			}
			tNear = Math.max(tNear, t1);
			tFar = Math.min(tFar, t2);
			if (tNear > tFar) {
				return Double.NaN;
			}
		}
		return Double.isFinite(tNear) ? tNear : Double.NaN;
	}

	/** The horizontal right axis {@code (x, z)} for a look vector, falling back to the yaw. */
	private static double[] rightAxis(Pos look, float yawDeg) {
		double fx = look.x();
		double fz = look.z();
		double flat = Math.sqrt(fx * fx + fz * fz);
		if (flat < EPSILON || !Double.isFinite(flat)) {
			double yaw = Math.toRadians(yawDeg);
			fx = -Math.sin(yaw);
			fz = Math.cos(yaw);
			flat = Math.sqrt(fx * fx + fz * fz);
			if (!(flat > EPSILON)) {
				return new double[] {0.0, 0.0};
			}
		}
		fx /= flat;
		fz /= flat;
		return new double[] {-fz, fx};
	}

	private static double finiteOrZero(double x) {
		return Double.isFinite(x) ? x : 0.0;
	}
}
