import SwiftUI
import PhotosUI
import UIKit

/// The message field at the bottom of chat, with the mic beside it. Once there is text, the mic gives way to send.
struct Composer: View {
    @Binding var text: String
    @Binding var photoData: Data?
    var canSend: Bool
    var isListening: Bool
    var isFocused: FocusState<Bool>.Binding
    var onSend: () -> Void
    var onMicPress: () -> Void
    var onMicRelease: () -> Void
    @State private var selection: PhotosPickerItem?
    @State private var photoError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            if let photoData, let image = UIImage(data: photoData) {
                HStack(spacing: Space.s2) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 54, height: 54)
                        .clipShape(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                    Text("Photo ready to send")
                        .textStyle(.caption, weight: .medium)
                        .foregroundStyle(Palette.inkMuted)
                    Spacer()
                    Button {
                        self.photoData = nil
                        photoError = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 22))
                            .foregroundStyle(Palette.inkMuted)
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.pressable)
                    .accessibilityLabel("Remove photo")
                }
                .padding(.horizontal, Space.s2)
            }

            if let photoError {
                Text(photoError)
                    .textStyle(.caption)
                    .foregroundStyle(Palette.danger)
                    .padding(.horizontal, Space.s2)
            }

            HStack(alignment: .bottom, spacing: 8) {
                PhotosPicker(selection: $selection, matching: .images, photoLibrary: .shared()) {
                    Image(systemName: "photo")
                        .font(.system(size: 19, weight: .medium))
                        .foregroundStyle(Palette.inkMuted)
                        .frame(width: 44, height: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.pressable)
                .accessibilityLabel("Attach a photo")

                TextField("Message TinyMe", text: $text, prompt: Text("Message TinyMe").foregroundStyle(Palette.inkMuted), axis: .vertical)
                    .textStyle(.body)
                    .foregroundStyle(Palette.ink)
                    .lineLimit(1...5)
                    .focused(isFocused)
                    .padding(.horizontal, 20)
                    .padding(.vertical, Space.s4)
                    .frame(minHeight: 56)
                    .glassSurface(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))

                if showsMic {
                    MicButton(isListening: isListening, onPress: onMicPress, onRelease: onMicRelease)
                } else {
                    sendButton
                }
            }
        }
        .padding(.horizontal, Space.s4)
        .padding(.top, Space.s2)
        .padding(.bottom, Space.s2)
        .onChange(of: selection) { _, selected in
            guard let selected else { return }
            Task {
                do {
                    guard let data = try await selected.loadTransferable(type: Data.self),
                          let optimized = ChatPhotoCompressor.optimizedJPEG(data) else {
                        photoError = "Couldn't read that photo. Choose another one."
                        return
                    }
                    photoData = optimized
                    photoError = nil
                } catch {
                    photoError = "Couldn't read that photo. Choose another one."
                }
                selection = nil
            }
        }
    }

    private var sendButton: some View {
        Button(action: onSend) {
            Image(systemName: "arrow.up")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(canSend ? Palette.onBrand : Palette.inkMuted)
                .frame(width: 56, height: 56)
                .background(canSend ? Palette.brand : Palette.surfaceSunken, in: Circle())
                .shadow(color: canSend ? Palette.pressEdge : .clear, radius: 0, y: 3)
        }
        .buttonStyle(.pressable)
        .disabled(!canSend)
        .animation(.snappy(duration: 0.2), value: canSend)
        .accessibilityLabel("Send")
    }

    private var showsMic: Bool {
        photoData == nil && (isListening || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

}
