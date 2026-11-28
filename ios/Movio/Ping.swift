import Foundation

/// Anonymous check-ins for the admin page: a random id for this iPhone and the app version,
/// sent when Movio opens and every few minutes while something plays. No names, emails or accounts.
enum Ping {
    private static var id: String {
        if let v = UserDefaults.standard.string(forKey: "device_id") { return v }
        let v = UUID().uuidString.lowercased()
        UserDefaults.standard.set(v, forKey: "device_id")
        return v
    }
    private static func send(_ extra: [String: Any] = [:]) {
        guard let url = URL(string: "\(Store.helper)/ping") else { return }
        var body: [String: Any] = ["d": id, "p": "ios", "v": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""]
        body.merge(extra) { $1 }
        var r = URLRequest(url: url, timeoutInterval: 8)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: r).resume()
    }
    static func open() { send() }
    /// kind: "movie", "tv" or "live".
    static func watching(_ title: String, _ kind: String) { send(["w": title, "k": kind]) }
    static func stopped() { send(["stop": true]) }
}
