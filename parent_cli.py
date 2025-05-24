import ssl
import socket
import argparse
import sqlite3
import sys

HOST = '192.168.0.102'  # Windows host Wi-Fi IP
PORT = 7100

def send_command(command):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.load_verify_locations('server.crt')
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            with context.wrap_socket(s, server_hostname=HOST) as secure_socket:
                print(f"Attempting to connect to {HOST}:{PORT}")
                secure_socket.settimeout(5)
                secure_socket.connect((HOST, PORT))
                secure_socket.sendall(command.encode())
                response = secure_socket.recv(1024).decode()
                print(f"Response: {response}")
    except ConnectionRefusedError:
        print(f"Error: Connection refused to {HOST}:{PORT}. Is the server running?")
        sys.exit(1)
    except ssl.SSLError as e:
        print(f"SSL Error: {e}")
        sys.exit(1)
    except socket.timeout:
        print(f"Error: Connection timed out to {HOST}:{PORT}")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

def view_data(device_id, data_type):
    try:
        with sqlite3.connect('monitoring_data.db') as db:
            cursor = db.execute(
                "SELECT data, timestamp FROM logs WHERE device_id = ? AND data_type = ? ORDER BY timestamp DESC",
                (device_id, data_type)
            )
            rows = cursor.fetchall()
            if rows:
                for row in rows:
                    print(f"[{row[1]}] {row[0]}")
            else:
                print(f"No {data_type} data found for device {device_id}")
    except sqlite3.Error as e:
        print(f"Database error: {e}")
        sys.exit(1)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--command', help='Command to send')
    parser.add_argument('--view-data', help='View data for device')
    parser.add_argument('--data-type', help='Data type to view')
    args = parser.parse_args()
    if args.command:
        send_command(args.command)
    elif args.view_data and args.data_type:
        view_data(args.view_data, args.data_type)
    else:
        print("Please provide --command or --view-data and --data-type")
        sys.exit(1)

if __name__ == "__main__":
    main()
