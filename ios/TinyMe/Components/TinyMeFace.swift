import SwiftUI

/// TinyMe's face: a disc with two eyes, and alive. It breathes, blinks and glances about, looks up while it thinks,
/// and hops when it's pleased. Still when `isAlive` is off or Reduce Motion is on.
struct TinyMeFace: View {
    enum Mood {
        /// No streak: eyes shut, slow breathing.
        case sleepy
        case awake
        /// A milestone: smiling eyes and a hop now and then.
        case pleased
        /// Working on a reply: eyes up, looking from side to side.
        case thinking
    }

    var size: CGFloat
    var mood: Mood = .awake
    /// Off for faces that repeat down a list, so only the newest one moves.
    var isAlive = true
    /// A tap gets a squish and a blink back.
    var reactsToTap = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var eyesClosed = false
    @State private var gaze = CGSize.zero
    @State private var breathing = false
    @State private var squished = false

    private struct Liveliness: Equatable {
        let mood: Mood
        let moves: Bool
    }

    var body: some View {
        HStack(spacing: size * 0.16) {
            eye
            eye
        }
        .offset(x: gaze.width * size * 0.07, y: gaze.height * size * 0.06)
        .foregroundStyle(mood == .sleepy ? Palette.inkMuted : Palette.onBrand)
        .frame(width: size, height: size)
        .background(background, in: Circle())
        .scaleEffect(breathing ? 1.04 : 1)
        .scaleEffect(x: squished ? 1.1 : 1, y: squished ? 0.88 : 1, anchor: .bottom)
        .onTapGesture {
            guard reactsToTap, !reduceMotion else { return }
            Task { await react() }
        }
        .allowsHitTesting(reactsToTap)
        .task(id: Liveliness(mood: mood, moves: isAlive && !reduceMotion)) {
            settle()
            guard isAlive, !reduceMotion else { return }
            withAnimation(.easeInOut(duration: mood == .sleepy ? 3.2 : 2.2).repeatForever(autoreverses: true)) {
                breathing = true
            }
            await live()
        }
    }

    // MARK: - Life

    private func live() async {
        switch mood {
        case .sleepy:
            return
        case .awake:
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Double.random(in: 2.6...5.2)))
                guard !Task.isCancelled else { return }
                if Int.random(in: 0..<4) == 0 {
                    await glance()
                } else {
                    // Mostly single blinks, now and then a quick double.
                    for _ in 0..<(Int.random(in: 0..<5) == 0 ? 2 : 1) { await blink() }
                }
            }
        case .thinking:
            var side: CGFloat = -1
            while !Task.isCancelled {
                withAnimation(.easeInOut(duration: 0.55)) { gaze = CGSize(width: side * 0.9, height: -0.8) }
                try? await Task.sleep(for: .milliseconds(Int.random(in: 700...1100)))
                if Int.random(in: 0..<4) == 0 { await blink() }
                side = -side
            }
        case .pleased:
            await hop()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Double.random(in: 4...7)))
                guard !Task.isCancelled else { return }
                await hop()
            }
        }
    }

    private func blink() async {
        guard !Task.isCancelled else { return }
        withAnimation(.easeIn(duration: 0.07)) { eyesClosed = true }
        try? await Task.sleep(for: .milliseconds(110))
        withAnimation(.easeOut(duration: 0.12)) { eyesClosed = false }
        try? await Task.sleep(for: .milliseconds(180))
    }

    /// A look to one side, held for a moment.
    private func glance() async {
        let target = CGSize(width: Bool.random() ? 1 : -1, height: CGFloat.random(in: -0.4...0.2))
        withAnimation(.easeInOut(duration: 0.22)) { gaze = target }
        try? await Task.sleep(for: .milliseconds(Int.random(in: 700...1300)))
        guard !Task.isCancelled else { return }
        withAnimation(.easeInOut(duration: 0.22)) { gaze = .zero }
        try? await Task.sleep(for: .milliseconds(260))
    }

    private func hop() async {
        guard !Task.isCancelled else { return }
        withAnimation(.easeOut(duration: 0.12)) { squished = true }
        try? await Task.sleep(for: .milliseconds(120))
        withAnimation(.spring(duration: 0.5, bounce: 0.55)) { squished = false }
        try? await Task.sleep(for: .milliseconds(500))
    }

    private func react() async {
        await hop()
        if mood == .awake || mood == .thinking { await blink() }
    }

    /// Back to a resting face, without animating there.
    private func settle() {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            eyesClosed = false
            gaze = .zero
            breathing = false
            squished = false
        }
    }

    // MARK: - Looks

    private var background: Color {
        switch mood {
        case .sleepy: Palette.surfaceSunken
        case .awake, .thinking: Palette.brand
        case .pleased: Palette.sunny
        }
    }

    @ViewBuilder
    private var eye: some View {
        switch mood {
        case .sleepy:
            Capsule().frame(width: size * 0.17, height: size * 0.05)
        case .awake, .thinking:
            Capsule()
                .frame(width: size * 0.125, height: size * 0.19)
                .scaleEffect(y: eyesClosed ? 0.12 : 1)
        case .pleased:
            // Smiling eyes: the top half of a ring.
            Circle()
                .trim(from: 0.5, to: 1)
                .stroke(style: StrokeStyle(lineWidth: size * 0.06, lineCap: .round))
                .frame(width: size * 0.17, height: size * 0.17)
                .offset(y: size * 0.04)
        }
    }
}
