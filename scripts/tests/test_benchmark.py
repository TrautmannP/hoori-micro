"""Accounting and bounded offered-load regression checks, not Hoori transport acceptance."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark import load, percentile, summarize


class BenchmarkTest(unittest.TestCase):
    def test_rejections_and_missing_arrivals_are_not_successful_throughput(self):
        result = summarize([(200, 10, 0), (503, 1, 0), (0, 50, 0)], 5, 2, 100, .01)
        self.assertEqual((result["successful"], result["failed"], result["not_started"]), (1, 2, 2))
        self.assertEqual(result["successful_per_minute"], 30)
        self.assertEqual(result["error_fraction"], .8)
        self.assertFalse(result["accepted"])
        self.assertEqual(percentile([10, 1, 50], .99), 50)
        self.assertIsNone(percentile([], .99))

    def test_open_load_has_bounded_work_and_does_not_retry(self):
        observed = []

        class Peer(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                observed.append(json.loads(body))
                time.sleep(.1)
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Peer) as peer:
            thread = threading.Thread(target=peer.serve_forever)
            thread.start()
            try:
                result = load(peer.server_port, "/echo", {"value": "hello"}, .3, 1, 100, 1000, .01)
            finally:
                peer.shutdown()
                thread.join()
        self.assertEqual(result["offered"], 30)
        self.assertEqual(result["started"], len(observed))
        self.assertEqual(result["successful"], len(observed))
        self.assertGreater(result["not_started"], 0)
        self.assertEqual(result["failed"], 0)
        self.assertFalse(result["accepted"])


if __name__ == "__main__":
    unittest.main()
