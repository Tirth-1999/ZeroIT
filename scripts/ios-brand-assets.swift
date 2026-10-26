import AppKit
import Foundation

// Render both native launch art and every launcher slot from the shared geometry.
struct Brand: Decodable {
    struct Shape: Decodable {
        let color: String
        let segments: [[Segment]]
    }
    enum Segment: Decodable {
        case command(String), value(Double)
        init(from decoder: Decoder) throws {
            let box = try decoder.singleValueContainer()
            if let value = try? box.decode(Double.self) { self = .value(value) }
            else { self = .command(try box.decode(String.self)) }
        }
        var number: CGFloat { if case .value(let n) = self { return CGFloat(n) }; preconditionFailure("Expected coordinate") }
        var command: String { if case .command(let s) = self { return s }; preconditionFailure("Expected path command") }
    }
    let background: String
    let cornerRadius: Double
    let shapes: [Shape]
}
func color(_ hex: String) -> CGColor {
    let rgb = UInt32(hex.dropFirst(), radix: 16)!
    return NSColor(srgbRed: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255, blue: CGFloat(rgb & 255) / 255, alpha: 1).cgColor
}
let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let brand = try JSONDecoder().decode(Brand.self, from: Data(contentsOf: root.appendingPathComponent("branding/zeroit-mark.json")))
func drawMark(_ context: CGContext, rounded: Bool) {
    context.setFillColor(color(brand.background))
    let bounds = CGRect(x: 0, y: 0, width: 1024, height: 1024)
    if rounded {
        context.addPath(CGPath(roundedRect: bounds, cornerWidth: brand.cornerRadius, cornerHeight: brand.cornerRadius, transform: nil))
        context.fillPath()
    } else { context.fill(bounds) }
    // JSON/Compose/SVG coordinates increase downwards; Core Graphics increases upwards.
    context.translateBy(x: 0, y: 1024)
    context.scaleBy(x: 1, y: -1)
    for stroke in brand.shapes {
        context.setFillColor(color(stroke.color))
        for segment in stroke.segments {
            switch segment[0].command {
            case "M": context.move(to: CGPoint(x: segment[1].number, y: segment[2].number))
            case "L": context.addLine(to: CGPoint(x: segment[1].number, y: segment[2].number))
            case "C": context.addCurve(to: CGPoint(x: segment[5].number, y: segment[6].number), control1: CGPoint(x: segment[1].number, y: segment[2].number), control2: CGPoint(x: segment[3].number, y: segment[4].number))
            case "Z": context.closePath()
            default: preconditionFailure("Unsupported path segment")
            }
        }
        context.fillPath()
    }
}
let assets = root.appendingPathComponent("iosApp/iosApp/Assets.xcassets")
let output = assets.appendingPathComponent("AppIcon.appiconset")
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
var images: [[String: String]] = []
let slots: [(String, String, [Int])] = [
    ("iphone", "20x20", [2, 3]), ("iphone", "29x29", [2, 3]),
    ("iphone", "40x40", [2, 3]), ("iphone", "60x60", [2, 3]),
    ("ipad", "20x20", [1, 2]), ("ipad", "29x29", [1, 2]),
    ("ipad", "40x40", [1, 2]), ("ipad", "76x76", [1, 2]),
    ("ipad", "83.5x83.5", [2]), ("ios-marketing", "1024x1024", [1])
]
for (idiom, size, scales) in slots {
    let points = Double(size.components(separatedBy: "x")[0])!
    for scale in scales {
        let pixels = Int(points * Double(scale))
        let filename = "icon-\(pixels).png"
        let context = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: pixels * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        context.scaleBy(x: CGFloat(pixels) / 1024, y: CGFloat(pixels) / 1024)
        drawMark(context, rounded: false)
        let bitmap = NSBitmapImageRep(cgImage: context.makeImage()!)
        // Always overwrite: stale icons must never survive a rebrand regeneration.
        try bitmap.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent(filename))
        images.append(["idiom": idiom, "size": size, "scale": "\(scale)x", "filename": filename])
    }
}
let contents: [String: Any] = ["images": images, "info": ["author": "xcode", "version": 1]]
try JSONSerialization.data(withJSONObject: contents, options: [.prettyPrinted, .sortedKeys]).write(to: output.appendingPathComponent("Contents.json"))
let launch = assets.appendingPathComponent("LaunchLogo.imageset")
try FileManager.default.createDirectory(at: launch, withIntermediateDirectories: true)
var box = CGRect(x: 0, y: 0, width: 104, height: 104)
let pdf = CGContext(launch.appendingPathComponent("launch-logo.pdf") as CFURL, mediaBox: &box, nil)!
pdf.beginPDFPage(nil)
pdf.scaleBy(x: 104/1024, y: 104/1024)
drawMark(pdf, rounded: true)
pdf.endPDFPage(); pdf.closePDF()
try """
{"images":[{"filename":"launch-logo.pdf","idiom":"universal"}],"info":{"author":"xcode","version":1},"properties":{"preserves-vector-representation":true}}
""".write(to: launch.appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
print("Generated \(images.count) ZeroIT icon slots and native vector launch logo")
