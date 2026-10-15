import UIKit

/// Encodes chat photos small enough to fit the server's base64 request limit.
@MainActor
enum ChatPhotoCompressor {
    private static let maximumJPEGBytes = 1_100_000

    static func optimizedJPEG(_ data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let sourceWidth = image.cgImage?.width ?? Int(image.size.width * image.scale)
        let sourceHeight = image.cgImage?.height ?? Int(image.size.height * image.scale)
        var longestSide = min(1024, max(max(sourceWidth, sourceHeight), 1))

        // 1.1 MB of JPEG bytes becomes about 1.47 MB of base64 in the JSON request.
        // An explicit renderer scale avoids multiplying the target by the screen scale.
        while longestSide >= 320 {
            let ratio = CGFloat(longestSide) / CGFloat(max(max(sourceWidth, sourceHeight), 1))
            let size = CGSize(width: max(1, (CGFloat(sourceWidth) * ratio).rounded()),
                              height: max(1, (CGFloat(sourceHeight) * ratio).rounded()))
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = true
            let renderer = UIGraphicsImageRenderer(size: size, format: format)
            let resized = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }

            for quality: CGFloat in [0.78, 0.68, 0.58, 0.48, 0.38] {
                if let jpeg = resized.jpegData(compressionQuality: quality), jpeg.count <= maximumJPEGBytes {
                    return jpeg
                }
            }
            longestSide = Int((Double(longestSide) * 0.82).rounded(.down))
        }
        return nil
    }
}
