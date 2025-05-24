import socket
import ssl
import json
import threading
import sqlite3
import argparse
from datetime import datetime

# Server config
HOST = "192.168.0.102"
PORT = 7100
ALLOWED_CLIENTS = {"child_device_001": "secure_password"}
DB_PATH = "monitoring_data.db"
CERT_FILE = "server.crt"
KEY_FILE = "server.key"

# ========== DB INIT ========== #
def init_db():
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS monitoring_data
                 (client_id TEXT, type TEXT, data TEXT, timestamp TEXT)''')
    conn.commit()
    conn.close()

# ========== STORE DATA ========== #
def store_data(client_id, type, data, timestamp):
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    c.execute("INSERT INTO monitoring_data (client_id, type, data, timestamp) VALUES (?, ?, ?, ?)",
              (client_id, type, data, timestamp))
    conn.commit()
    conn.close()

# ========== SERVER HANDLER ========== #
def handle_client(conn, addr):
    print(f"[+] New connection from {addr}")
    try:
        raw_data = conn.recv(1024).decode()
        print(f"[DEBUG] Raw auth data from {addr}: {raw_data}")
        auth_data = json.loads(raw_data)

        client_id = auth_data.get("client_id")
        secret = auth_data.get("secret")

        if client_id in ALLOWED_CLIENTS and secret == ALLOWED_CLIENTS[client_id]:
            conn.send("AUTH_SUCCESS".encode())
            print(f"[+] Authenticated client: {client_id}")
        else:
            conn.send("AUTH_FAILED".encode())
            print(f"[!] Authentication failed for: {client_id}")
            return

        while True:
            data = conn.recv(1024).decode()
            if not data:
                print(f"[-] Connection closed by {addr}")
                break
            print(f"[DEBUG] Raw data from {client_id}: {data}")
            try:
                data_json = json.loads(data)
                store_data(client_id, data_json["type"], data_json["data"], data_json["timestamp"])
                print(f"[+] Stored data from {client_id}: {data_json}")
            except json.JSONDecodeError as je:
                print(f"[!] JSON decode error from {addr}: {je}")
    except Exception as e:
        print(f"[!] Error handling client {addr}: {e}")
    finally:
        conn.close()

# ========== SERVER MAIN ========== #
def run_server():
    init_db()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=CERT_FILE, keyfile=KEY_FILE)

    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket = context.wrap_socket(server_socket, server_side=True)
    server_socket.bind((HOST, PORT))
    server_socket.listen(5)
    print(f"[✓] Server listening on {HOST}:{PORT}")

    try:
        while True:
            conn, addr = server_socket.accept()
            threading.Thread(target=handle_client, args=(conn, addr)).start()
    except KeyboardInterrupt:
        print("[x] Server shutting down.")
    finally:
        server_socket.close()

# ========== CLI COMMAND SENDER ========== #
def send_command(command, client_id="child_device_001"):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.load_verify_locations(CERT_FILE)

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s = context.wrap_socket(s, server_hostname=HOST)
        s.connect((HOST, PORT))
        print(f"[→] Connected to server at {HOST}:{PORT}")

        # Send authentication
        auth = {"client_id": client_id, "secret": ALLOWED_CLIENTS.get(client_id)}
        s.send(json.dumps(auth).encode())
        auth_response = s.recv(1024).decode()
        print(f"[⇄] Server response: {auth_response}")

        if auth_response != "AUTH_SUCCESS":
            print("[!] Authentication failed.")
            return

        # Send command
        timestamp = datetime.utcnow().isoformat()
        payload = {"type": "command", "data": command, "timestamp": timestamp}
        s.send(json.dumps(payload).encode())
        print(f"[✓] Command sent: {command}")

# ========== VIEW DATA ========== #
def view_data(client_id, data_type=None):
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    query = "SELECT type, data, timestamp FROM monitoring_data WHERE client_id = ?"
    params = [client_id]
    if data_type:
        query += " AND type = ?"
        params.append(data_type)
    c.execute(query, params)
    rows = c.fetchall()
    if not rows:
        print("[i] No data found.")
    for row in rows:
        print(f"Type: {row[0]}, Data: {row[1]}, Timestamp: {row[2]}")
    conn.close()

# ========== MAIN ========== #
def main():
    parser = argparse.ArgumentParser(description="Parental Monitor - Server and CLI")
    parser.add_argument("--run-server", action="store_true", help="Run the parent server")
    parser.add_argument("--command", help="Send a command to a child device")
    parser.add_argument("--view-data", help="View data for a specific client ID")
    parser.add_argument("--data-type", help="Filter data by type (e.g., location, keylog)")
    args = parser.parse_args()

    if args.run_server:
        run_server()
    elif args.command:
        send_command(args.command)
    elif args.view_data:
        view_data(args.view_data, args.data_type)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
