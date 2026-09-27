import SwiftUI

/// Top-down duck that moves from the walk command, as if the phone were the body.
struct DuckSimView: View {
    var pose: Pose
    var scene: Scene
    var gait: Float
    var hfov: Float
    var trail: [(Float, Float)]

    var body: some View {
        Canvas { context, size in
            guard size.width > 0, size.height > 0 else { return }
            let originX = size.width / 2
            let originY = size.height - 36
            let pxPerM = (size.height - 56) / 1.7
            func world(_ wx: Float, _ wy: Float) -> CGPoint {
                let dx = wx - pose.x
                let dy = wy - pose.y
                let ahead = dx * cos(pose.heading) + dy * sin(pose.heading)
                let side = dx * sin(pose.heading) - dy * cos(pose.heading)
                return duck(ahead, side, originX, originY, pxPerM)
            }
            drawGrid(context, size, world)
            drawCone(context, originX, originY, pxPerM)
            drawTrail(context, world)
            drawMarker(context, originX, originY, pxPerM)
            drawDuck(context, originX, originY)
            context.draw(
                Text("simulated duck").font(.system(size: 13)).foregroundStyle(Color.muted),
                at: CGPoint(x: 16, y: 8),
                anchor: .topLeading
            )
            var scale = Path()
            scale.move(to: CGPoint(x: 16, y: size.height - 16))
            scale.addLine(to: CGPoint(x: 16 + pxPerM, y: size.height - 16))
            context.stroke(scale, with: .color(Color.muted), lineWidth: 2)
            context.draw(
                Text("1 m").font(.system(size: 12)).foregroundStyle(Color.muted),
                at: CGPoint(x: 16, y: size.height - 20),
                anchor: .bottomLeading
            )
        }
        .accessibilityLabel("Simulated duck following the marker")
    }

    private func drawGrid(_ context: GraphicsContext, _ size: CGSize, _ world: (Float, Float) -> CGPoint) {
        let spacing: Float = 0.25
        let limit: Float = 3
        var x = floor((pose.x - limit) / spacing) * spacing
        while x <= pose.x + limit {
            var path = Path()
            path.move(to: world(x, pose.y - 3))
            path.addLine(to: world(x, pose.y + 3))
            context.stroke(path, with: .color(Color.grid), lineWidth: 1)
            x += spacing
        }
        var y = floor((pose.y - limit) / spacing) * spacing
        while y <= pose.y + limit {
            var path = Path()
            path.move(to: world(pose.x - 3, y))
            path.addLine(to: world(pose.x + 3, y))
            context.stroke(path, with: .color(Color.grid), lineWidth: 1)
            y += spacing
        }
    }

    private func drawCone(_ context: GraphicsContext, _ originX: CGFloat, _ originY: CGFloat, _ pxPerM: CGFloat) {
        let span = hfov / 2
        let reach: Float = 1.55
        var path = Path()
        path.move(to: duck(0, 0, originX, originY, pxPerM))
        path.addLine(to: duck(cos(span) * reach, -sin(span) * reach, originX, originY, pxPerM))
        path.addLine(to: duck(cos(span) * reach, sin(span) * reach, originX, originY, pxPerM))
        path.closeSubpath()
        context.fill(path, with: .color(Color.cone))
    }

    private func drawTrail(_ context: GraphicsContext, _ world: (Float, Float) -> CGPoint) {
        guard trail.count >= 2 else { return }
        var path = Path()
        for (index, point) in trail.enumerated() {
            let screen = world(point.0, point.1)
            if index == 0 { path.move(to: screen) } else { path.addLine(to: screen) }
        }
        context.stroke(path, with: .color(Color.trail), lineWidth: 2)
    }

    private func drawMarker(_ context: GraphicsContext, _ originX: CGFloat, _ originY: CGFloat, _ pxPerM: CGFloat) {
        guard scene.visible else { return }
        let reach = min(scene.distance, 1.55)
        let point = duck(cos(scene.angle) * reach, sin(scene.angle) * reach, originX, originY, pxPerM)
        let mark = Path(ellipseIn: CGRect(x: point.x - 8, y: point.y - 8, width: 16, height: 16))
        context.fill(mark, with: .color(Color.person))
        context.draw(
            Text("marker").font(.system(size: 12)).foregroundStyle(Color.person),
            at: CGPoint(x: point.x, y: point.y - 14),
            anchor: .bottom
        )
    }

    private func drawDuck(_ context: GraphicsContext, _ originX: CGFloat, _ originY: CGFloat) {
        let step = 7 * sin(gait)
        let body: [(Float, Float)] = [(-16, -12), (4, -14), (8, 0), (4, 14), (-16, 12)]
        var path = Path()
        for (index, part) in body.enumerated() {
            let point = local(part.0, part.1, originX, originY)
            if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        context.fill(path, with: .color(Color.duck))
        foot(context, step - 4, 12, originX, originY)
        foot(context, -step - 4, -12, originX, originY)
        let head = local(16, 0, originX, originY)
        context.fill(Path(ellipseIn: CGRect(x: head.x - 9, y: head.y - 9, width: 18, height: 18)), with: .color(Color.duck))
        var beak = Path()
        let beakPoints = [local(22, -3, originX, originY), local(30, 0, originX, originY), local(22, 3, originX, originY)]
        beak.move(to: beakPoints[0])
        beak.addLine(to: beakPoints[1])
        beak.addLine(to: beakPoints[2])
        beak.closeSubpath()
        context.fill(beak, with: .color(Color.beak))
        let eye = local(18, 4, originX, originY)
        context.fill(Path(ellipseIn: CGRect(x: eye.x - 1.6, y: eye.y - 1.6, width: 3.2, height: 3.2)), with: .color(Color.ink))
    }

    private func foot(_ context: GraphicsContext, _ forward: Float, _ side: Float, _ originX: CGFloat, _ originY: CGFloat) {
        let center = local(forward, side, originX, originY)
        let oval = Path(ellipseIn: CGRect(x: center.x - 5, y: center.y - 3, width: 10, height: 6))
        context.fill(oval, with: .color(Color.beak))
    }

    private func duck(_ ahead: Float, _ side: Float, _ originX: CGFloat, _ originY: CGFloat, _ pxPerM: CGFloat) -> CGPoint {
        CGPoint(x: originX + CGFloat(side) * pxPerM, y: originY - CGFloat(ahead) * pxPerM)
    }

    private func local(_ forward: Float, _ left: Float, _ originX: CGFloat, _ originY: CGFloat) -> CGPoint {
        CGPoint(x: originX - CGFloat(left), y: originY - CGFloat(forward))
    }
}
