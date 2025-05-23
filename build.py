# build.py
import argparse
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

def create_project_dir(temp_dir, ip, port, icon):
    project_dir = Path(temp_dir) / "childmonitoring"
    project_dir.mkdir(parents=True, exist_ok=True)
    
    # Copy pre-built Android project (assumed to exist in ./childmonitoring)
    shutil.copytree("childmonitoring", project_dir, dirs_exist_ok=True)
    
    # Update server config in MainActivity.java and MonitoringService.java
    for file in ["MainActivity.java", "MonitoringService.java"]:
        path = project_dir / f"app/src/main/java/org/example/childmonitoring/{file}"
        with open(path, "r") as f:
            content = f.read()
        content = content.replace("private static final String SERVER_HOST = \"192.168.1.100\";",
                                 f"private static final String SERVER_HOST = \"{ip}\";")
        content = content.replace("private static final int SERVER_PORT = 8080;",
                                 f"private static final int SERVER_PORT = {port};")
        with open(path, "w") as f:
            f.write(content)
    
    # Copy icon if provided
    if icon and os.path.exists(icon):
        shutil.copy(icon, project_dir / "app/src/main/res/mipmap/ic_launcher.png")

    return project_dir

def build_apk(project_dir, output):
    try:
        # Build with Gradle
        subprocess.run(["./gradlew", "assembleDebug"], cwd=project_dir / "app", check=True)
        
        # Use apktool to decompile and recompile
        apk_path = project_dir / "app/build/outputs/apk/debug/app-debug.apk"
        subprocess.run(["apktool", "d", str(apk_path), "-o", str(project_dir / "decompiled")], check=True)
        subprocess.run(["apktool", "b", str(project_dir / "decompiled"), "-o", str(project_dir / "unsigned.apk")], check=True)
        
        # Sign with sign.jar
        subprocess.run(["java", "-jar", "sign.jar", "--apks", str(project_dir / "unsigned.apk"), "--out", output], check=True)
        print(f"APK generated successfully at: {output}")
    except subprocess.CalledProcessError as e:
        print(f"Build failed: {e}")

def main():
    parser = argparse.ArgumentParser(description="Build Parental Monitoring APK")
    parser.add_argument("--build", action="store_true", help="Trigger APK build")
    parser.add_argument("--ip", required=True, help="Server IP address")
    parser.add_argument("--port", type=int, required=True, help="Server port")
    parser.add_argument("--output", default="child.apk", help="Output APK file path")
    parser.add_argument("--icon", help="Path to app icon (PNG)")
    args = parser.parse_args()

    if not args.build:
        print("Use --build to generate the APK")
        return
    if not (0 <= args.port <= 65535):
        print("Error: Port must be between 0 and 65535")
        return
    if not args.output.endswith(".apk"):
        args.output += ".apk"

    with tempfile.TemporaryDirectory() as temp_dir:
        project_dir = create_project_dir(temp_dir, args.ip, args.port, args.icon)
        build_apk(project_dir, args.output)

if __name__ == "__main__":
    main()
