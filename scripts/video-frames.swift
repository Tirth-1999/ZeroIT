import Foundation
import AVFoundation
import AppKit

// Export bounded video frames for visual analysis of a recorded interaction.
let args = CommandLine.arguments
guard args.count == 4, let step = Double(args[3]), step > 0 else {
    fatalError("Usage: video-frames <video.mp4> <output-directory> <step-seconds>")
}
let asset = AVURLAsset(url: URL(fileURLWithPath: args[1]))
let duration = CMTimeGetSeconds(asset.duration)
guard duration.isFinite, duration > 0, duration / step <= 1000 else {
    fatalError("Invalid duration or more than 1000 requested frames")
}
try FileManager.default.createDirectory(atPath: args[2], withIntermediateDirectories: true)
let generator = AVAssetImageGenerator(asset: asset)
generator.appliesPreferredTrackTransform = true
generator.maximumSize = CGSize(width: 270, height: 600)
generator.requestedTimeToleranceBefore = .zero
generator.requestedTimeToleranceAfter = .zero
for t in stride(from: 0.0, to: duration, by: step) {
    let image = try generator.copyCGImage(at: CMTime(seconds: t, preferredTimescale: 600), actualTime: nil)
    let bitmap = NSBitmapImageRep(cgImage: image)
    let data = bitmap.representation(using: .png, properties: [:])!
    let filename = String(format: "frame-%07.3f.png", t)
    try data.write(to: URL(fileURLWithPath: args[2]).appendingPathComponent(filename))
}
print("Exported frames; video duration: \(duration) seconds")
