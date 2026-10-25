package kwamster.direction;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import kwamster.Kwamster;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

/**
 * Draws the above-head bars (S12, §5.11a), ported from Direction Indicator's {@code IndicatorRenderer}:
 * one flat, camera-facing bar just above the head and below the nametag of every player
 * {@link HeadBarTracker#shouldDraw} accepts, green when they move the way they look, red when they
 * backpedal and yellow when they are not meaningfully moving. The colour is decided by
 * {@link HeadBarTracker}; this class only places the quads.
 *
 * <p>Registered on Fabric's {@code WorldRenderEvents.END_MAIN}, which fires inside the main render pass
 * right before the world renderer's own {@code endBatch()}. ({@code BEFORE_DEBUG_RENDER} runs in the
 * extraction region in fabric-rendering-v1 16.2.x and would hand out the previous frame's pose.)
 * {@code RenderTypes.debugQuads()} is POSITION_COLOR quads, translucent, no depth write and no back-face
 * culling, so the backdrop and the fill share a plane and draw in order, behind terrain rather than
 * through it. Hidden with F1 and while the master switch (R4) is off.
 */
public final class HeadBarRenderer {
	/** How far the dark backdrop extends past the coloured fill on every side, in blocks. */
	private static final double BORDER = 0.022;

	/**
	 * How far the fill is pulled toward the camera, in blocks, to force its draw order.
	 * {@code debugQuads()} is {@code sortOnUpload()}, so the batch is re-sorted back to front by quad
	 * centroid every frame. The backdrop and the fill are coplanar with identical centroids, a tie that
	 * float rounding broke differently every frame anything moved, so the backdrop sometimes composited
	 * over the fill and the bar flickered while moving. Two millimetres decides the order by a real margin;
	 * depth write is off, so the depth test is unaffected.
	 */
	private static final double FILL_SORT_BIAS = 0.002;

	private HeadBarRenderer() {
	}

	/** The {@code WorldRenderEvents.END_MAIN} callback. Never throws into vanilla (R3). */
	public static void render(WorldRenderContext context) {
		try {
			draw(context);
		} catch (Throwable t) {
			LogLimiter.error("HeadBarRenderer.render", "Head bar rendering failed", t);
		}
	}

	private static void draw(WorldRenderContext context) {
		KwamsterConfig cfg = Kwamster.config();
		KwamsterConfig.Direction dir = cfg.direction;
		HeadBarTracker tracker = HeadBarTracker.get();
		if (!cfg.general.enabled || !dir.headBarEnabled || tracker == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer self = mc.player;
		if (level == null || self == null || mc.options.hideGui) {
			return;
		}
		MultiBufferSource consumers = context.consumers();
		PoseStack poseStack = context.matrices();
		if (consumers == null || poseStack == null) {
			return;
		}

		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		Vec3 up = vec(camera.upVector());
		Vec3 right = vec(camera.leftVector()).scale(-1.0);

		double halfWidth = dir.barWidth / 200.0;
		double halfHeight = dir.barHeight / 200.0;
		double clearance = dir.headClearance / 100.0;

		// true matches what vanilla resolves for a Player: the frozen-tick flag never applies to them.
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Matrix4f matrix = poseStack.last().pose();
		VertexConsumer buffer = consumers.getBuffer(RenderTypes.debugQuads());

		for (AbstractClientPlayer player : level.players()) {
			if (!HeadBarTracker.shouldDraw(self, player, dir)) {
				continue;
			}
			// Your own bar would sit on the first-person near plane; draw it only with a detached camera (F5).
			if (player == self && !camera.isDetached()) {
				continue;
			}
			// World rendering puts the camera at the origin, so work camera-relative.
			double x = Mth.lerp(partialTick, player.xo, player.getX());
			double y = Mth.lerp(partialTick, player.yo, player.getY());
			double z = Mth.lerp(partialTick, player.zo, player.getZ());
			double height = tracker.interpolatedHeight(player, partialTick);
			Vec3 center = new Vec3(x, y + height + clearance, z).subtract(camPos);
			Vec3 fillCenter = center.lengthSqr() > 1.0E-6
					? center.subtract(center.normalize().scale(FILL_SORT_BIAS))
					: center;

			quad(buffer, matrix, center, right, up, halfWidth + BORDER, halfHeight + BORDER, dir.backdropArgb);
			quad(buffer, matrix, fillCenter, right, up, halfWidth, halfHeight, colour(tracker.heading(player, dir), dir));
		}
	}

	private static int colour(HeadBarMotion.Heading heading, KwamsterConfig.Direction dir) {
		return switch (heading) {
			case FORWARD -> dir.forwardArgb;
			case BACKWARD -> dir.backwardArgb;
			case IDLE -> dir.idleArgb;
		};
	}

	private static void quad(VertexConsumer buffer, Matrix4f matrix, Vec3 center, Vec3 right, Vec3 up,
			double halfWidth, double halfHeight, int argb) {
		Vec3 h = right.scale(halfWidth);
		Vec3 v = up.scale(halfHeight);
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		vertex(buffer, matrix, center.subtract(h).subtract(v), r, g, b, a);
		vertex(buffer, matrix, center.add(h).subtract(v), r, g, b, a);
		vertex(buffer, matrix, center.add(h).add(v), r, g, b, a);
		vertex(buffer, matrix, center.subtract(h).add(v), r, g, b, a);
	}

	private static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3 pos, int r, int g, int b, int a) {
		buffer.addVertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z).setColor(r, g, b, a);
	}

	private static Vec3 vec(Vector3fc v) {
		return new Vec3(v.x(), v.y(), v.z());
	}
}
