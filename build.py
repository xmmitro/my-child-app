import argparse
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
import ipaddress
import xml.etree.ElementTree as ET
import re

def create_project_dir(temp_dir, ip, port, icon, app_name):
    project_dir = Path(temp_dir) / "childmonitoring"
    print(f"Creating project dir at: {project_dir}")
    project_dir.mkdir(parents=True, exist_ok=True)

    # Check if source directory exists
    source_dir = Path("childmonitoring")
    if not source_dir.exists():
        raise FileNotFoundError("Source directory 'childmonitoring' not found in workspace")

    # Validate IP address format
    try:
        ipaddress.ip_address(ip)
    except ValueError:
        raise ValueError(f"Invalid IP address: {ip}")

    # Validate port
    if not (0 <= port <= 65535):
        raise ValueError("Port must be between 0 and 65535")

    # Validate app name
    if not app_name or not app_name.strip():
        raise ValueError("App name must be non-empty")

    print(f"Injecting IP: {ip}, Port: {port}, App Name: {app_name} into project files")

    # Copy pre-built Android project
    shutil.copytree(source_dir, project_dir, dirs_exist_ok=True)

    # Ensure gradlew is executable
    gradlew_path = project_dir / "gradlew"
    if gradlew_path.exists():
        os.chmod(gradlew_path, 0o755)
    else:
        raise FileNotFoundError(f"gradlew not found in {project_dir}")

    # Update WebSocket URL in Java files
    java_files = ["MonitoringService.java"]
    websocket_url = f"ws://{ip}:{port}"
    for file in java_files:
        path = project_dir / f"app/src/main/java/com/xyz/child/{file}"
        if not path.exists():
            print(f"Warning: {file} not found at {path}, skipping injection")
            continue
        with open(path, "r") as f:
            content = f.read()
        original_content = content
        # Match SERVER_URL with optional comment
        pattern = r'private\s+static\s+final\s+String\s+SERVER_URL\s*=\s*"[^"]*"\s*;[^\n]*'
        replacement = f'private static final String SERVER_URL = "{websocket_url}";'
        content = re.sub(pattern, replacement, content)
        if content == original_content:
            server_url_line = next((line for line in content.splitlines() if "SERVER_URL" in line), None)
            print(f"Warning: No SERVER_URL replacements made in {file}. Expected pattern: {pattern}")
            print(f"SERVER_URL line: {server_url_line if server_url_line else 'Not found'}")
            print(f"File content preview: {content[:200]}...")
        else:
            print(f"Successfully updated {file} with SERVER_URL: {websocket_url}")
        with open(path, "w") as f:
            f.write(content)

    # Update app name in strings.xml
    strings_path = project_dir / "app/src/main/res/values/strings.xml"
    if not strings_path.exists():
        strings_path.parent.mkdir(parents=True, exist_ok=True)
        with open(strings_path, "w") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>')
        print(f"Created strings.xml at {strings_path}")
    tree = ET.parse(strings_path)
    root = tree.getroot()
    app_name_updated = False
    for string in root.findall("string"):
        if string.get("name") == "app_name":
            string.text = app_name
            app_name_updated = True
            print(f"Updated app_name to '{app_name}' in strings.xml")
            break
    if not app_name_updated:
        new_string = ET.Element("string", name="app_name")
        new_string.text = app_name
        root.append(new_string)
        print(f"Created app_name '{app_name}' in strings.xml")
    tree.write(strings_path)

    # Copy icon if provided
    if icon and os.path.exists(icon):
        icon_path = Path(icon)
        if icon_path.suffix.lower() not in [".png", ".webp"]:
            raise ValueError("Icon must be a PNG or WEBP file")
        for density in ["hdpi", "mdpi", "xhdpi", "xxhdpi", "xxxhdpi"]:
            dest = project_dir / f"app/src/main/res/mipmap-{density}/ic_launcher.webp"
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(icon, dest)
            print(f"Copied icon to {dest}")
    else:
        print(f"Warning: Icon path '{icon}' invalid or not found, using existing icon")

    return project_dir

def build_apk(project_dir, output):
    for tool in ["apktool.jar", "sign.jar"]:
        if not os.path.exists(tool):
            raise FileNotFoundError(f"Required tool {tool} not found in current directory")

    try:
        gradlew_path = project_dir / "gradlew"
        if not gradlew_path.exists():
            raise FileNotFoundError(f"gradlew not found in {project_dir}")

        print("Building APK using Gradle...")
        subprocess.run([str(gradlew_path), "assembleDebug", "--no-configuration-cache"], cwd=project_dir, check=True)

        apk_path = project_dir / "app/build/outputs/apk/debug/app-debug.apk"
        if not apk_path.exists():
            raise FileNotFoundError(f"Gradle build failed: {apk_path} not found")

        decompiled_dir = project_dir / "decompiled"
        print("Decompiling APK...")
        subprocess.run(["java", "-jar", "apktool.jar", "d", str(apk_path), "-o", str(decompiled_dir)], check=True)

        print("Recompiling APK...")
        recompiled_apk = project_dir / "unsigned.apk"
        subprocess.run(["java", "-jar", "apktool.jar", "b", str(decompiled_dir), "-o", str(recompiled_apk)], check=True)

        if not recompiled_apk.exists():
            raise FileNotFoundError(f"APK recompilation failed: {recompiled_apk} not found")

        print("Signing APK...")
        subprocess.run(["java", "-jar", "sign.jar", "--apks", str(recompiled_apk), "--out", output], check=True)

        if not os.path.exists(output):
            raise FileNotFoundError(f"APK signing failed: {output} not found")

        print(f"✅ APK generated successfully at: {output}")
    except subprocess.CalledProcessError as e:
        print(f"❌ Build failed: {e}")
        raise
    except FileNotFoundError as e:
        print(f"❌ Error: {e}")
        raise

def main():
    parser = argparse.ArgumentParser(description="Build Parental Monitoring APK")
    parser.add_argument("--build", action="store_true", help="Trigger APK build")
    parser.add_argument("--ip", required=True, help="Server IP address")
    parser.add_argument("--port", type=int, required=True, help="Server port")
    parser.add_argument("--output", default="child.apk", help="Output APK file path")
    parser.add_argument("--icon", help="Path to app icon (PNG or WEBP)")
    parser.add_argument("--app-name", default="Child Monitoring", help="Custom app name")
    args = parser.parse_args()

    if not args.build:
        print("Use --build to generate the APK")
        return

    if not args.output.endswith(".apk"):
        args.output += ".apk"

    try:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = create_project_dir(temp_dir, args.ip, args.port, args.icon, args.app_name)
            build_apk(project_dir, args.output)
    except Exception as e:
        print(f"❌ Error during build process: {e}")
        raise

if __name__ == "__main__":
    main()
