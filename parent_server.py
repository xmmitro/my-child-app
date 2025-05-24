import socket
import ssl
import sqlite3
import threading
import datetime

HOST = '0.0.0.0'
PORT = 7100

def init_db():
    with sqlite3.connect('monitoring_data.db') as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT,
                data_type TEXT,
                data TEXT,
                timestamp TEXT
            )
        """)
        db.commit()
        print("Database initialized with logs table")

def handle_client(conn, addr):
    print(f"Connected by {addr}")
    try:
        data = conn.recv(1024).decode()
        if data:
            print(f"Received: {data}")
            try:
                device_id, data_type, content = data.split(':', 2)
                timestamp = datetime.datetime.now().isoformat()
                with sqlite3.connect('monitoring_data.db') as db:
                    db.execute(
                        "INSERT INTO logs (device_id, data_type, data, timestamp) VALUES (?, ?, ?, ?)",
                        (device_id, data_type, content, timestamp)
                    )
                    db.commit()
            except ValueError:
                print(f"Invalid data format: {data}")
        conn.sendall(b"ACK")
    except Exception as e:
        print(f"Error: {e}")
    finally:
        conn.close()

def main():
    init_db()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile="server.crt", keyfile="server.key")
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.bind((HOST, PORT))
    server_socket.listen()
    print(f"Server listening on {HOST}:{PORT}")
    with context.wrap_socket(server_socket, server_side=True) as secure_socket:
        while True:
            conn, addr = secure_socket.accept()
            threading.Thread(target=handle_client, args=(conn, addr)).start()

if __name__ == "__main__":
    main()
