package me.raddatz.cindy.ui.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import me.raddatz.cindy.core.Point
import me.raddatz.cindy.core.demo.ExerciseDemo
import me.raddatz.cindy.core.demo.StickPose
import me.raddatz.cindy.core.demo.StickProp
import me.raddatz.cindy.ui.theme.CindyTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Draws an [ExerciseDemo] as a looping stick figure (iOS `StickFigureDemoView`), together with the
 * floor, the bar and the phone, so every exercise shows the phone in the same spot. Needs no
 * assets and no camera.
 *
 * Draw order: props, far limbs, a phone lying [between the hands][StickProp.Phone.betweenHands],
 * torso with near limbs and head, and finally every other phone on top of everything.
 */
@Composable
fun StickFigureDemo(demo: ExerciseDemo, isPaused: Boolean, modifier: Modifier = Modifier) {
    val phase = remember(demo) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(demo, isPaused) {
        if (isPaused) return@LaunchedEffect
        while (true) {
            withFrameMillis {
                phase.doubleValue = phaseAt(System.currentTimeMillis() / 1000.0, demo.cycle)
            }
        }
    }
    val brand = CindyTheme.colors.brand
    val propColor = MaterialTheme.colorScheme.onSurfaceVariant
    val phoneBody = CindyTheme.colors.phoneBody
    Canvas(
        modifier
            .aspectRatio(demo.aspectRatio.toFloat())
            .clearAndSetSemantics {},
    ) {
        val pose = demo.start.blended(demo.end, phase.doubleValue)
        val scene = Scene(demo, size)
        drawProps(demo, scene, propColor)
        drawLimbs(demo, pose, scene, brand.copy(alpha = 0.35f)) { demo.farSide(it, pose) }
        for (prop in demo.props) {
            if (prop is StickProp.Phone && prop.betweenHands) drawPhone(demo, prop.x, scene, phoneBody, brand)
        }
        drawNearBody(demo, pose, scene, brand)
        for (prop in demo.props) {
            if (prop is StickProp.Phone && !prop.betweenHands) drawPhone(demo, prop.x, scene, phoneBody, brand)
        }
    }
}

/** Eases out to `end` and back once per cycle, lingering a little at both ends. */
internal fun phaseAt(seconds: Double, cycle: Double): Double {
    val elapsed = seconds % cycle
    return 0.5 - 0.5 * cos(2 * PI * elapsed / cycle)
}

/** Maps the demo's coordinates onto the canvas, fitting the movement's own bounding box. */
private class Scene(demo: ExerciseDemo, size: Size) {
    val scale: Double
    val originX: Double
    val originY: Double
    val left: Double
    val right: Double

    init {
        val box = demo.bounds
        scale = min(size.width / box.width, size.height / box.height)
        originX = (size.width - box.width * scale) / 2 - box.minX * scale
        originY = (size.height - box.height * scale) / 2 - box.minY * scale
        left = originX + box.minX * scale
        right = originX + box.maxX * scale
    }

    operator fun invoke(p: Point): Offset = Offset((originX + p.x * scale).toFloat(), (originY + p.y * scale).toFloat())

    fun y(value: Double): Float = (originY + value * scale).toFloat()
}

private fun DrawScope.stroke(demo: ExerciseDemo, scene: Scene) = Stroke(
    width = (scene.scale * demo.headRadius * 0.42).toFloat(),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

private fun Path.polyline(points: List<Offset>) {
    moveTo(points[0].x, points[0].y)
    for (point in points.drop(1)) lineTo(point.x, point.y)
}

private fun DrawScope.drawLimbs(
    demo: ExerciseDemo,
    pose: StickPose,
    scene: Scene,
    color: Color,
    transform: (Point) -> Point,
) {
    val path = Path().apply {
        // Starts at the neck so the far arm is joined to the body instead of floating beside it.
        polyline(listOf(pose.neck, pose.shoulder, pose.elbow, pose.hand).map { scene(transform(it)) })
        polyline(listOf(pose.hip, pose.knee, pose.foot).map { scene(transform(it)) })
    }
    drawPath(path, color, style = stroke(demo, scene))
}

/** Torso, near arm and leg, and the head. */
private fun DrawScope.drawNearBody(demo: ExerciseDemo, pose: StickPose, scene: Scene, color: Color) {
    val torso = Path().apply {
        polyline(listOf(scene(pose.neck), scene(pose.hip)))
        polyline(listOf(scene(pose.neck), scene(pose.shoulder)))
        polyline(listOf(scene(pose.neck), scene(pose.head)))
    }
    drawPath(torso, color, style = stroke(demo, scene))
    drawLimbs(demo, pose, scene, color) { it }
    drawCircle(color, radius = (scene.scale * demo.headRadius).toFloat(), center = scene(pose.head))
}

private fun DrawScope.drawProps(demo: ExerciseDemo, scene: Scene, color: Color) {
    for (prop in demo.props) {
        when (prop) {
            is StickProp.Floor -> drawPropLine(demo, scene, prop.y, 0.0, 1.0, 0.16, color)
            is StickProp.Bar -> drawPropLine(demo, scene, prop.y, 0.12, 0.88, 0.32, color)
            is StickProp.Phone -> Unit // drawn around the figure, see StickFigureDemo
        }
    }
}

/** A horizontal prop spanning a fraction of the scene's width; [width] is a multiple of the head radius. */
private fun DrawScope.drawPropLine(
    demo: ExerciseDemo,
    scene: Scene,
    y: Double,
    from: Double,
    to: Double,
    width: Double,
    color: Color,
) {
    val span = scene.right - scene.left
    val lineY = scene.y(y)
    drawLine(
        color,
        start = Offset((scene.left + span * from).toFloat(), lineY),
        end = Offset((scene.left + span * to).toFloat(), lineY),
        strokeWidth = (scene.scale * demo.headRadius * width).toFloat(),
        cap = StrokeCap.Round,
    )
}

/** The phone as a thin slab lying flat on the floor, its lit screen on top; about a head long. */
private fun DrawScope.drawPhone(demo: ExerciseDemo, x: Double, scene: Scene, body: Color, screen: Color) {
    val unit = scene.scale * demo.headRadius
    val width = unit * 2.2
    val height = unit * 0.45
    val floor = scene(Point(x, demo.floorY))
    // Resting on top of the floor line, whose stroke is 0.16 head radii thick.
    val top = floor.y - unit * 0.08 - height
    val left = floor.x - width / 2
    drawRoundRect(
        body,
        topLeft = Offset(left.toFloat(), top.toFloat()),
        size = Size(width.toFloat(), height.toFloat()),
        cornerRadius = CornerRadius((height * 0.35).toFloat()),
    )
    drawRoundRect(
        screen,
        topLeft = Offset((left + width * 0.06).toFloat(), top.toFloat()),
        size = Size((width * 0.88).toFloat(), (height * 0.35).toFloat()),
        cornerRadius = CornerRadius((height * 0.15).toFloat()),
    )
}
