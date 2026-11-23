import CoreBluetooth
import Foundation

/// Finding Jams nearby over Bluetooth (works on mobile data or another Wi-Fi too, within ~10 m).
/// The host's phone announces a Tunio Jam service; a phone nearby connects for a moment and reads how to join
/// (server address, Jam id, name, password or not). The queue and sync still go through the home server.
/// Same service as the Android app. An iPhone announces only while Tunio is open on screen.
final class JamBeacon: NSObject {
    static let shared = JamBeacon()
    static let service = CBUUID(string: "7D2EA1C0-5C4B-4F1E-9A43-7E1A0B3C6A10")
    static let info = CBUUID(string: "7D2EA1C0-5C4B-4F1E-9A43-7E1A0B3C6A11")

    private var peripheralManager: CBPeripheralManager?
    private var payload = Data()
    private var hosting = false
    private var serviceAdded = false

    private var central: CBCentralManager?
    private var seen: [UUID: CBPeripheral] = [:]
    private var results: [JamFound] = []
    private var finishScan: (([JamFound]) -> Void)?

    // MARK: the host announces its Jam

    func startHosting(server: String, id: String, name: String, locked: Bool, host: String) {
        let o: [String: Any] = ["u": server, "j": id, "n": String(name.prefix(40)), "l": locked, "h": String(host.prefix(30))]
        payload = (try? JSONSerialization.data(withJSONObject: o)) ?? Data()
        guard !hosting else { return }
        hosting = true
        if let pm = peripheralManager { peripheralManagerDidUpdateState(pm) } else {
            peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
        }
    }

    func stopHosting() {
        hosting = false
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
        serviceAdded = false
    }

    // MARK: phones nearby look for Jams

    /// Jams announced by phones nearby (scans for a few seconds, then reads each one).
    func scan(seconds: Double = 4) async -> [JamFound] {
        await withCheckedContinuation { (cont: CheckedContinuation<[JamFound], Never>) in
            DispatchQueue.main.async {
                guard self.finishScan == nil else { cont.resume(returning: []); return }
                self.seen = [:]
                self.results = []
                self.finishScan = { cont.resume(returning: $0) }
                if let c = self.central { self.centralManagerDidUpdateState(c) } else {
                    self.central = CBCentralManager(delegate: self, queue: .main)
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { self.central?.stopScan() }
                // reading each phone takes a moment
                DispatchQueue.main.asyncAfter(deadline: .now() + seconds + 4) { self.done() }
            }
        }
    }

    private func done() {
        central?.stopScan()
        for p in seen.values { central?.cancelPeripheralConnection(p) }
        let r = results
        let f = finishScan
        finishScan = nil
        f?(r)
    }

    static func parse(_ data: Data?) -> JamFound? {
        guard let data, let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              var server = o["u"] as? String, let id = o["j"] as? String, !server.isEmpty, !id.isEmpty else { return nil }
        while server.hasSuffix("/") { server.removeLast() }
        let info = JamInfo(id: id, name: (o["n"] as? String) ?? "Jam", locked: o["l"] as? Bool ?? false, host: o["h"] as? String, members: nil, now: nil)
        return JamFound(server: server, info: info)
    }
}

extension JamBeacon: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ pm: CBPeripheralManager) {
        guard hosting, pm.state == .poweredOn else { return }
        if !serviceAdded {
            let c = CBMutableCharacteristic(type: JamBeacon.info, properties: .read, value: nil, permissions: .readable)
            let s = CBMutableService(type: JamBeacon.service, primary: true)
            s.characteristics = [c]
            pm.add(s)
            serviceAdded = true
        } else if !pm.isAdvertising {
            pm.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [JamBeacon.service], CBAdvertisementDataLocalNameKey: "Tunio Jam"])
        }
    }

    func peripheralManager(_ pm: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard hosting, error == nil else { return }
        pm.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [JamBeacon.service], CBAdvertisementDataLocalNameKey: "Tunio Jam"])
    }

    func peripheralManager(_ pm: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == JamBeacon.info else { pm.respond(to: request, withResult: .attributeNotFound); return }
        guard request.offset <= payload.count else { pm.respond(to: request, withResult: .invalidOffset); return }
        request.value = payload.subdata(in: request.offset..<payload.count)
        pm.respond(to: request, withResult: .success)
    }
}

extension JamBeacon: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        guard finishScan != nil else { return }
        if c.state == .poweredOn {
            c.scanForPeripherals(withServices: [JamBeacon.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        } else if c.state == .poweredOff || c.state == .unauthorized || c.state == .unsupported {
            done()
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        guard finishScan != nil, seen[p.identifier] == nil, seen.count < 8 else { return }
        seen[p.identifier] = p
        p.delegate = self
        c.connect(p)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        p.discoverServices([JamBeacon.service])
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == JamBeacon.service }) else { central?.cancelPeripheralConnection(p); return }
        p.discoverCharacteristics([JamBeacon.info], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let c = service.characteristics?.first(where: { $0.uuid == JamBeacon.info }) else { central?.cancelPeripheralConnection(p); return }
        p.readValue(for: c)
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        if let f = JamBeacon.parse(c.value), !results.contains(where: { $0.info.id == f.info.id }) { results.append(f) }
        central?.cancelPeripheralConnection(p)
    }
}
