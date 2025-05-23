# parent_server.py
import socket
import ssl
import json
import threading
import sqlite3
from datetime import datetime

HOST = "0.0.0.0"
PORT = 8080
ALLOWED_CLIENTS = {"child_device_001": "secure_password"}
DB_PATH = "monitoring_data.db"

def init_db():
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS monitoring_data
                 (client_id TEXT, type TEXT, data TEXT, timestamp TEXT)''')
    conn.commit()
    conn.close()

def handle_client(conn, addr):
    print(f"New connection from {addr}")
    try:
        auth_data = json.loads(conn.recv(1024).decode())
        client_id = auth_data.get("client_id")
        secret = auth_data.get("secret")
        if client_id in ALLOWED_CLIENTS and secret == ALLOWED_CLIENTS[client_id]:
            conn.send("AUTH_SUCCESS".encode())
            print(f"Authenticated client: {client_id}")
        else:
            conn.send("AUTH_FAILED".encode())
            return

        while True:
            data = conn.recv(1024).decode()
            if not data:
                break
            data_json = json.loads(data)
            store_data(client_id, data_json["type"], data_json["data"], data_json["timestamp"])
            print(f"Received from {client_id}: {data_json}")
    except Exception as e:
        print(f"Error with {addr}: {e}")
    finally:
        conn.close()

def store_data(client_id, type, data, timestamp):
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    c.execute("INSERT INTO monitoring_data (client_id, type, data, timestamp) VALUES (?, ?, ?, ?)",
              (client_id, type, data, timestamp))
    conn.commit()
    conn.close()

def main():
    init_db()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile="server.crt", keyfile="server.key")
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket = context.wrap_socket(server_socket, server_side=True)
    server_socket.bind((HOST, PORT))
    server_socket.listen(5)
    print(f"Server listening on {HOST}:{PORT}")
    try:
        while True:
            conn, addr = server_socket.accept()
            threading.Thread(target=handle_client, args=(conn, addr)).start()
    finally:
        server_socket.close()

if __name__ == "__main__":
    main()
