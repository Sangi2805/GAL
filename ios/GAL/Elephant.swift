import SwiftUI

/// The elephant's faces, the same set as on Android.
enum ElephantMood {
    case none, smug, bored, disappointed, horrified, angry, proud
}

/// Everything that changes from frame to frame.
struct ElephantPose {
    var mood: ElephantMood = .none
    /// 1 faces right, -1 faces left.
    var facing: CGFloat = 1
    var walking = false
    /// 0...1 position in the walk cycle.
    var walkPhase: CGFloat = 0
    /// Trunk angle in degrees: 0 hangs down, 90 points forward, 180 points up.
    var trunkAngle: CGFloat = 20
    var trunkCurl: CGFloat = 0.35
    /// 0...1: ears lifted out.
    var earFlap: CGFloat = 0
    /// 1 open, 0 shut.
    var blink: CGFloat = 1
    var eyesShut = false
    /// 0...1 how cross she is, and how proud of you.
    var anger: CGFloat = 0
    var proud: CGFloat = 0
    /// 0...1 loop for steam, sparkles and z's.
    var phase: CGFloat = 0
    var headTilt: CGFloat = 0
}

private extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

/// Draws the pink elephant whole, side on, head turned to us. A Swift port of BlobPainter.kt on Android, with
/// the same proportions and colours. Her feet touch the bottom of the box she is given.
enum ElephantPainter {
    static let skinLight = Color(hex: 0xFFD0E4)
    static let skinMid = Color(hex: 0xF8B2D1)
    static let skinDark = Color(hex: 0xEE8DB9)
    static let skinFar = Color(hex: 0xE07FAA)
    static let innerEar = Color(hex: 0xFF9EC4)
    static let rim = Color(hex: 0x9C3D6E)
    static let nail = Color(hex: 0xFFF4F8)
    static let ink = Color(hex: 0x3A1530)
    static let cheek = Color(hex: 0xFF6F9F, alpha: 0.59)
    static let bow = Color(hex: 0xFF5C8A)
    static let bowKnot = Color(hex: 0xFF7FA3)
    static let anger = Color(hex: 0xFF3B3B)
    static let steam = Color(hex: 0xC9C9D3)
    static let sparkle = Color(hex: 0xFFC93C)
    static let sweat = Color(hex: 0x7EC8F0)
    static let tongue = Color(hex: 0xFF7A8A)
    static let highlight = Color.white.opacity(0.53)

    private static let eyeDX: [CGFloat] = [-0.30, 0.22]

    /// Brow ends and arch per mood, for each eye, in head radii (positive is down).
    private static func brows(_ mood: ElephantMood) -> [[CGFloat]]? {
        switch mood {
        case .smug: return [[0.02, 0.02, 0], [-0.08, -0.04, -0.1]]
        case .bored: return [[0.06, 0.06, 0], [0.06, 0.06, 0]]
        case .disappointed: return [[-0.09, 0.05, 0], [-0.09, 0.05, 0]]
        case .horrified: return [[-0.13, -0.07, -0.1], [-0.13, -0.07, -0.1]]
        case .angry: return [[0.12, -0.08, 0], [0.12, -0.08, 0]]
        case .proud: return [[-0.04, -0.04, -0.08], [-0.04, -0.04, -0.08]]
        case .none: return nil
        }
    }

    private static func lid(_ mood: ElephantMood) -> CGFloat {
        switch mood {
        case .smug: return 0.42
        case .bored: return 0.58
        case .disappointed: return 0.26
        case .angry: return 0.30
        default: return 0
        }
    }

    // MARK: Small helpers

    private static func oval(_ cx: CGFloat, _ cy: CGFloat, _ rx: CGFloat, _ ry: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: cx - rx, y: cy - ry, width: rx * 2, height: ry * 2))
    }

    private static func rotated(_ ctx: GraphicsContext, _ degrees: CGFloat, _ px: CGFloat, _ py: CGFloat) -> GraphicsContext {
        var c = ctx
        c.translateBy(x: px, y: py)
        c.rotate(by: .degrees(Double(degrees)))
        c.translateBy(x: -px, y: -py)
        return c
    }

    private static func stroke(_ width: CGFloat) -> StrokeStyle {
        StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round)
    }

    private static func line(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: x1, y: y1))
        p.addLine(to: CGPoint(x: x2, y: y2))
        return p
    }

    // MARK: Drawing

    /// [bw] x [bh] is her box this frame; [nominal] fixes the line widths so a squash does not change them.
    static func draw(_ context: GraphicsContext, cx: CGFloat, cy: CGFloat, bw: CGFloat, bh: CGFloat, nominal: CGFloat, pose: ElephantPose) {
        guard bw > 0, bh > 0, nominal > 0 else { return }
        let u = bw
        let rimW = nominal * 0.016
        let inkW = nominal * 0.020
        let lashW = nominal * 0.011
        let skin = GraphicsContext.Shading.linearGradient(
            Gradient(colors: [skinLight, skinDark]),
            startPoint: CGPoint(x: 0, y: cy - bh / 2),
            endPoint: CGPoint(x: 0, y: cy + bh / 2)
        )

        var ctx = context
        if pose.facing < 0 {
            ctx.translateBy(x: cx, y: cy)
            ctx.scaleBy(x: -1, y: 1)
            ctx.translateBy(x: -cx, y: -cy)
        }

        let ground = cy + bh / 2
        let cycle = pose.walkPhase * 2 * .pi
        let bob = pose.walking ? u * 0.012 * abs(sin(cycle * 2)) : 0
        let puff = 1 + 0.10 * pose.proud

        let bodyX = cx - u * 0.09
        let bodyY = ground - bh * 0.36 + bob
        let bodyRx = u * 0.31 * puff
        let bodyRy = bh * 0.225 * puff
        let hipY = bodyY + bodyRy * 0.30

        func swing(_ offset: CGFloat) -> CGFloat {
            guard pose.walking else { return 0 }
            let p = (pose.walkPhase + offset).truncatingRemainder(dividingBy: 1)
            return sin(p * 2 * .pi) * 18
        }

        func leg(_ x: CGFloat, _ angle: CGFloat, far: Bool) {
            let w = u * 0.13
            let lift = angle > 0 ? u * 0.012 * (angle / 18) : 0
            let bottom = ground - lift
            let c = rotated(ctx, angle, x, hipY)
            let rect = CGRect(x: x - w / 2, y: hipY - w * 0.6, width: w, height: bottom - (hipY - w * 0.6))
            let shape = Path(roundedRect: rect, cornerRadius: w * 0.45)
            if far { c.fill(shape, with: .color(skinFar)) } else { c.fill(shape, with: skin) }
            c.stroke(shape, with: .color(rim), style: stroke(rimW))
            if !far {
                for k in 0..<3 {
                    let nx = x - w * 0.28 + CGFloat(k) * w * 0.28
                    c.fill(oval(nx, bottom - w * 0.14, w * 0.11, w * 0.10), with: .color(nail))
                }
            }
        }

        // Far legs, tail, body, near legs.
        leg(bodyX - bodyRx * 0.48, swing(0.50), far: true)
        leg(bodyX + bodyRx * 0.60, swing(0.25), far: true)

        let tx = bodyX - bodyRx * 0.97
        let ty = bodyY - bodyRy * 0.15
        let sway = pose.walking ? sin(pose.walkPhase * 4 * .pi) * u * 0.02 : 0
        var tail = Path()
        tail.move(to: CGPoint(x: tx, y: ty))
        tail.addQuadCurve(to: CGPoint(x: tx - u * 0.06 + sway, y: ty + u * 0.13), control: CGPoint(x: tx - u * 0.07, y: ty + u * 0.03))
        ctx.stroke(tail, with: .color(rim), style: stroke(rimW))
        ctx.fill(oval(tx - u * 0.06 + sway, ty + u * 0.145, u * 0.025, u * 0.025), with: .color(bowKnot))

        let body = oval(bodyX, bodyY, bodyRx, bodyRy)
        ctx.fill(body, with: skin)
        if pose.anger > 0.01 { ctx.fill(body, with: .color(anger.opacity(Double(0.24 * pose.anger)))) }
        ctx.stroke(body, with: .color(rim), style: stroke(rimW))
        leg(bodyX - bodyRx * 0.68, swing(0.0), far: false)
        leg(bodyX + bodyRx * 0.38, swing(0.75), far: false)
        rotated(ctx, -12, bodyX - bodyRx * 0.3, bodyY - bodyRy * 0.6)
            .fill(oval(bodyX - bodyRx * 0.30, bodyY - bodyRy * 0.685, bodyRx * 0.25, bodyRy * 0.135), with: .color(highlight))

        // Head, turned to us.
        let hx = bodyX + bodyRx * 0.84
        let hy = bodyY - bodyRy * 0.80
        let hr = u * 0.20
        let head = rotated(ctx, pose.headTilt, hx, hy + hr)

        // Ear, behind the head.
        let ex = hx - hr * 0.70
        let ey = hy + hr * 0.10
        let earCtx = rotated(head, -12 - 22 * pose.earFlap, ex + hr * 0.4, ey - hr * 0.3)
        let ear = Path(ellipseIn: CGRect(x: ex - hr * 0.90, y: ey - hr * 0.80, width: hr * 1.40, height: hr * 1.75))
        earCtx.fill(ear, with: skin)
        earCtx.stroke(ear, with: .color(rim), style: stroke(rimW))
        earCtx.fill(Path(ellipseIn: CGRect(x: ex - hr * 0.70, y: ey - hr * 0.58, width: hr * 0.95, height: hr * 1.33)), with: .color(innerEar))

        let face = oval(hx, hy, hr, hr * 0.95)
        head.fill(face, with: skin)
        if pose.anger > 0.01 { head.fill(face, with: .color(anger.opacity(Double(0.43 * pose.anger)))) }
        head.stroke(face, with: .color(rim), style: stroke(rimW))
        head.fill(Path(ellipseIn: CGRect(x: hx - hr * 0.6, y: hy - hr * 0.78, width: hr * 0.55, height: hr * 0.28)), with: .color(highlight))
        let cheekRect = CGRect(x: hx - hr * 0.35, y: hy + hr * 0.22, width: hr * 0.40, height: hr * 0.20)
        head.fill(Path(ellipseIn: cheekRect), with: .color(pose.anger > 0.5 ? anger.opacity(0.78) : cheek))

        // Eyes.
        let eyeY = hy - hr * (pose.eyesShut ? 0.05 : 0.08)
        for i in 0..<2 {
            let x = hx + hr * eyeDX[i]
            if pose.eyesShut {
                var p = Path()
                p.move(to: CGPoint(x: x - hr * 0.15, y: eyeY))
                p.addQuadCurve(to: CGPoint(x: x + hr * 0.15, y: eyeY), control: CGPoint(x: x, y: eyeY + hr * 0.14))
                head.stroke(p, with: .color(ink), style: stroke(inkW))
                continue
            }
            let wide = pose.mood == .horrified
            let rx = hr * 0.16 * (wide ? 1.1 : 1)
            let ry = hr * 0.21 * max(0.05, pose.blink * (wide ? 1.28 : 1))
            if wide { head.fill(oval(x, eyeY, rx * 1.4, ry * 1.35), with: .color(.white)) }
            let scale: CGFloat = wide ? 0.6 : 1
            head.fill(oval(x, eyeY, rx * scale, ry * scale), with: .color(ink))
            if ry > rx * 0.4 { head.fill(oval(x + rx * 0.35, eyeY - ry * 0.35, rx * 0.35, rx * 0.35), with: .color(.white)) }
            let side: CGFloat = i == 0 ? -1 : 1
            let lx = x + side * rx * 0.8
            let ly = eyeY - ry * 0.55
            head.stroke(line(lx, ly, lx + side * rx * 0.7, ly - rx * 0.4), with: .color(ink), style: stroke(lashW))
            head.stroke(line(lx - side * rx * 0.2, ly - ry * 0.3, lx + side * rx * 0.35, ly - ry * 0.3 - rx * 0.7), with: .color(ink), style: stroke(lashW))
            let l = lid(pose.mood)
            if l > 0 && ry > 1 {
                let top = eyeY - ry * 1.1
                let edge = eyeY - ry + 2 * ry * l
                head.fill(Path(CGRect(x: x - rx * 1.2, y: top, width: rx * 2.4, height: edge - top)), with: skin)
                head.stroke(line(x - rx * 1.05, edge, x + rx * 1.05, edge), with: .color(ink), style: stroke(lashW))
            }
        }

        // Brows.
        if let sets = brows(pose.mood) {
            let base = hy - hr * 0.42
            for i in 0..<2 {
                let x = hx + hr * eyeDX[i]
                let side: CGFloat = i == 0 ? -1 : 1
                let inner = sets[i][0], outer = sets[i][1], arch = sets[i][2]
                var p = Path()
                p.move(to: CGPoint(x: x - side * hr * 0.16, y: base + inner * hr))
                p.addQuadCurve(
                    to: CGPoint(x: x + side * hr * 0.16, y: base + outer * hr),
                    control: CGPoint(x: x, y: base + (inner + outer) / 2 * hr + arch * hr)
                )
                head.stroke(p, with: .color(ink), style: stroke(inkW))
            }
        }

        // Mouth.
        let mx = hx - hr * 0.08
        let my = hy + hr * 0.62
        var mouth = Path()
        switch pose.mood {
        case .smug:
            mouth.move(to: CGPoint(x: mx - hr * 0.12, y: my))
            mouth.addQuadCurve(to: CGPoint(x: mx + hr * 0.14, y: my - hr * 0.05), control: CGPoint(x: mx, y: my + hr * 0.08))
            head.stroke(mouth, with: .color(ink), style: stroke(inkW))
        case .bored:
            head.stroke(line(mx - hr * 0.1, my + hr * 0.02, mx + hr * 0.1, my + hr * 0.02), with: .color(ink), style: stroke(inkW))
        case .disappointed, .angry:
            mouth.move(to: CGPoint(x: mx - hr * 0.12, y: my + hr * 0.07))
            mouth.addQuadCurve(to: CGPoint(x: mx + hr * 0.12, y: my + hr * 0.07), control: CGPoint(x: mx, y: my - hr * 0.05))
            head.stroke(mouth, with: .color(ink), style: stroke(inkW))
        case .horrified:
            head.fill(oval(mx, my + hr * 0.05, hr * 0.08, hr * 0.11), with: .color(ink))
            head.fill(oval(mx, my + hr * 0.10, hr * 0.05, hr * 0.04), with: .color(tongue))
        case .proud:
            mouth.move(to: CGPoint(x: mx - hr * 0.15, y: my - hr * 0.02))
            mouth.addQuadCurve(to: CGPoint(x: mx + hr * 0.15, y: my - hr * 0.02), control: CGPoint(x: mx, y: my + hr * 0.24))
            mouth.closeSubpath()
            head.fill(mouth, with: .color(ink))
            head.fill(oval(mx, my + hr * 0.10, hr * 0.06, hr * 0.03), with: .color(tongue))
        case .none:
            mouth.move(to: CGPoint(x: mx - hr * 0.11, y: my - hr * 0.02))
            mouth.addQuadCurve(to: CGPoint(x: mx + hr * 0.11, y: my - hr * 0.02), control: CGPoint(x: mx, y: my + hr * 0.09))
            head.stroke(mouth, with: .color(ink), style: stroke(inkW))
        }

        // Trunk.
        drawTrunk(head, bx: hx + hr * 0.55, by: hy + hr * 0.30, u: u, nominal: nominal, rimW: rimW, pose: pose)

        // Bow.
        let bowCtx = rotated(head, -18, hx - hr * 0.35, hy - hr * 0.88)
        let s = hr * 0.26
        let bx = hx - hr * 0.35
        let by = hy - hr * 0.88
        for side: CGFloat in [-1, 1] {
            var wing = Path()
            wing.move(to: CGPoint(x: bx, y: by))
            wing.addLine(to: CGPoint(x: bx + side * s * 1.5, y: by - s * 0.9))
            wing.addQuadCurve(to: CGPoint(x: bx + side * s * 1.5, y: by + s * 0.9), control: CGPoint(x: bx + side * s * 2.0, y: by))
            wing.closeSubpath()
            bowCtx.fill(wing, with: .color(bow))
            bowCtx.stroke(wing, with: .color(rim), style: stroke(rimW))
        }
        bowCtx.fill(oval(bx, by, s * 0.45, s * 0.45), with: .color(bowKnot))
        bowCtx.stroke(oval(bx, by, s * 0.45, s * 0.45), with: .color(rim), style: stroke(rimW))

        // Extras.
        if pose.mood == .horrified {
            let x = hx + hr * 0.75
            let top = hy - hr * 0.9
            let size = hr * 0.18
            var drop = Path()
            drop.move(to: CGPoint(x: x, y: top))
            drop.addQuadCurve(to: CGPoint(x: x, y: top + size * 1.9), control: CGPoint(x: x + size, y: top + size * 1.4))
            drop.addQuadCurve(to: CGPoint(x: x, y: top), control: CGPoint(x: x - size, y: top + size * 1.4))
            ctx.fill(drop, with: .color(sweat))
        }
        if pose.anger > 0.3 {
            for k in 0..<2 {
                let p = (pose.phase + CGFloat(k) * 0.5).truncatingRemainder(dividingBy: 1)
                let px = hx + (k == 0 ? -hr * 0.5 : hr * 0.4)
                let py = hy - hr * 1.05 - p * hr * 0.9
                let r = hr * (0.12 + 0.12 * p)
                let shade = GraphicsContext.Shading.color(steam.opacity(Double(0.8 * pose.anger)))
                ctx.fill(oval(px, py, r, r), with: shade)
                ctx.fill(oval(px + r * 0.8, py + r * 0.2, r * 0.75, r * 0.75), with: shade)
            }
        }
        if pose.proud > 0.3 {
            for k in 0..<3 {
                let p = (pose.phase + CGFloat(k) / 3).truncatingRemainder(dividingBy: 1)
                let a = CGFloat(k) * 2.1 + 0.6
                let sx = bodyX + cos(a) * bodyRx * 1.35
                let sy = bodyY - bodyRy * 0.4 + sin(a) * bodyRy * 1.6
                let s = bodyRx * 0.10 * (0.4 + 0.6 * sin(p * .pi))
                var star = Path()
                star.move(to: CGPoint(x: sx, y: sy - s * 2))
                star.addLine(to: CGPoint(x: sx + s * 0.5, y: sy - s * 0.5))
                star.addLine(to: CGPoint(x: sx + s * 2, y: sy))
                star.addLine(to: CGPoint(x: sx + s * 0.5, y: sy + s * 0.5))
                star.addLine(to: CGPoint(x: sx, y: sy + s * 2))
                star.addLine(to: CGPoint(x: sx - s * 0.5, y: sy + s * 0.5))
                star.addLine(to: CGPoint(x: sx - s * 2, y: sy))
                star.addLine(to: CGPoint(x: sx - s * 0.5, y: sy - s * 0.5))
                star.closeSubpath()
                ctx.fill(star, with: .color(sparkle))
            }
        }
        if pose.eyesShut {
            for k in 0..<2 {
                let p = (pose.phase + CGFloat(k) * 0.5).truncatingRemainder(dividingBy: 1)
                let s = hr * (0.12 + 0.08 * CGFloat(k))
                let zx = hx + hr * 0.6 + p * hr * 0.4 + CGFloat(k) * hr * 0.25
                let zy = hy - hr * 1.0 - p * hr * 0.8 - CGFloat(k) * hr * 0.2
                var z = Path()
                z.move(to: CGPoint(x: zx, y: zy))
                z.addLine(to: CGPoint(x: zx + s, y: zy))
                z.addLine(to: CGPoint(x: zx, y: zy + s))
                z.addLine(to: CGPoint(x: zx + s, y: zy + s))
                ctx.stroke(z, with: .color(rim), style: stroke(nominal * 0.012))
            }
        }
    }

    private static func drawTrunk(_ ctx: GraphicsContext, bx: CGFloat, by: CGFloat, u: CGFloat, nominal: CGFloat, rimW: CGFloat, pose: ElephantPose) {
        let angle = min(max(pose.trunkAngle, -40), 200)
        let length = u * 0.27
        let a1 = Double(angle / 3) * .pi / 180
        let a3 = Double(angle) * .pi / 180
        let a2 = Double(angle * 0.8) * .pi / 180
        let p1 = CGPoint(x: bx + length * 0.40 * CGFloat(sin(a1)), y: by + length * 0.40 * CGFloat(cos(a1)))
        let tip = CGPoint(
            x: bx + length * CGFloat(sin(a3)) * 0.95,
            y: by + length * 0.35 * CGFloat(cos(a1)) + length * 0.75 * CGFloat(cos(a3))
        )
        let p2 = CGPoint(x: tip.x - length * 0.35 * CGFloat(sin(a2)), y: tip.y - length * 0.35 * CGFloat(cos(a2)))
        var path = Path()
        path.move(to: CGPoint(x: bx, y: by))
        path.addCurve(to: tip, control1: p1, control2: p2)
        let curl = min(max(pose.trunkCurl, 0), 1)
        if curl > 0.01 {
            let dx = tip.x - p2.x
            let dy = tip.y - p2.y
            let d = max(sqrt(dx * dx + dy * dy), 1)
            let ux = dx / d
            let uy = dy / d
            let hook = length * 0.16 * curl
            path.addQuadCurve(
                to: CGPoint(x: tip.x + ux * hook * 0.6 + uy * hook, y: tip.y + uy * hook * 0.6 - ux * hook),
                control: CGPoint(x: tip.x + ux * hook, y: tip.y + uy * hook)
            )
        }
        let width = nominal * 0.088
        ctx.stroke(path, with: .color(rim), style: StrokeStyle(lineWidth: width + rimW * 2, lineCap: .round, lineJoin: .round))
        ctx.stroke(path, with: .color(skinMid), style: StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round))
    }
}

/// The elephant on screen, drawn every frame. She breathes, blinks, and walks when [walking] is set.
struct ElephantView: View {
    var mood: ElephantMood = .none
    var facing: CGFloat = 1
    var walking = false
    var anger: CGFloat = 0
    var proud: CGFloat = 0
    var napping = false
    /// How far she has walked, in points: her steps follow it so her feet do not slide.
    var walkedDistance: CGFloat = 0

    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { context, size in
                let t = timeline.date.timeIntervalSinceReferenceDate
                let side = min(size.width, size.height) * 0.86
                let breath = CGFloat(sin(t * 2 * .pi / 2.4))
                var pose = ElephantPose()
                pose.mood = mood
                pose.facing = facing
                pose.walking = walking
                pose.walkPhase = walking ? (walkedDistance / max(side * 0.45, 1)).truncatingRemainder(dividingBy: 1) : 0
                pose.anger = anger
                pose.proud = proud
                pose.eyesShut = napping
                pose.phase = CGFloat((t / 1.4).truncatingRemainder(dividingBy: 1))
                // A blink every few seconds.
                let blinkT = t.truncatingRemainder(dividingBy: 4.3)
                pose.blink = blinkT < 0.17 ? CGFloat(1 - sin(blinkT / 0.17 * .pi)) : 1
                pose.trunkAngle = 20 + 9 * breath + (walking ? 10 * sin(pose.walkPhase * 4 * .pi) : 0)
                    + (proud > 0.3 ? 110 * proud : 0) + (napping ? -30 : 0)
                pose.earFlap = walking ? 0.25 : 0
                ElephantPainter.draw(
                    context,
                    cx: size.width / 2,
                    cy: size.height / 2,
                    bw: side * (1 - 0.03 * breath),
                    bh: side * (1 + 0.045 * breath),
                    nominal: side,
                    pose: pose
                )
            }
        }
        .accessibilityLabel("The pink elephant")
    }
}
