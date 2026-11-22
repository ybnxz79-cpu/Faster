#!/data/data/com.termux/files/usr/bin/python
import os
import json
import subprocess
import requests
from rich.console import Console
from rich.markdown import Markdown

console = Console()

API_KEY = os.getenv("GEMINI_API_KEY")
if not API_KEY or "यहाँ_अपनी_API_KEY" in API_KEY:
    console.print("[bold red]Error:[/bold red] पहले असली GEMINI_API_KEY सेट करो!")
    console.print("चलाओ: sed -i 's/यहाँ_अपनी_API_KEY_पेस्ट_करो/तुम्हारी_असली_KEY/' ~/.bashrc && source ~/.bashrc")
    exit(1)

MODEL = "gemini-3.8-flash"
URL = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent?key={API_KEY}"

def run_shell(command: str) -> str:
    console.print(f"\n[yellow]⚡ Command:[/yellow] [bold]{command}[/bold]")
    confirm = input("चलाऊं? (y/n): ").strip().lower()
    if confirm != "y":
        return "User cancelled command."
    try:
        res = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=60)
        out = (res.stdout + res.stderr).strip()
        return out if out else "Executed with no output."
    except Exception as e:
        return f"Error: {e}"

def write_file(filepath: str, content: str) -> str:
    console.print(f"\n[yellow]📝 Write file:[/yellow] [bold]{filepath}[/bold]")
    confirm = input("लिखूं? (y/n): ").strip().lower()
    if confirm != "y":
        return "User cancelled write_file."
    try:
        full_path = os.path.expanduser(filepath)
        os.makedirs(os.path.dirname(os.path.abspath(full_path)), exist_ok=True)
        with open(full_path, "w", encoding="utf-8") as f:
            f.write(content)
        return f"Wrote {filepath}"
    except Exception as e:
        return f"Error: {e}"

def read_file(filepath: str) -> str:
    try:
        full_path = os.path.expanduser(filepath)
        with open(full_path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception as e:
        return f"Error: {e}"

TOOLS_DECL = [{
    "function_declarations": [
        {
            "name": "run_shell",
            "description": "Run a shell command in Termux.",
            "parameters": {
                "type": "OBJECT",
                "properties": {"command": {"type": "STRING"}},
                "required": ["command"]
            }
        },
        {
            "name": "write_file",
            "description": "Create or overwrite a file.",
            "parameters": {
                "type": "OBJECT",
                "properties": {
                    "filepath": {"type": "STRING"},
                    "content": {"type": "STRING"}
                },
                "required": ["filepath", "content"]
            }
        },
        {
            "name": "read_file",
            "description": "Read a file's content.",
            "parameters": {
                "type": "OBJECT",
                "properties": {"filepath": {"type": "STRING"}},
                "required": ["filepath"]
            }
        }
    ]
}]

SYSTEM_PROMPT = (
    "तुम Fas AI हो—एक तेज़ और सीधा काम करने वाला टर्मिनल AI असिस्टेंट। "
    "यूज़र की भाषा (हिंदी, हिंग्लिश या इंग्लिश) में जवाब दो। "
    "ज़रूरत पड़ने पर run_shell, write_file, या read_file टूल कॉल करो।"
)

history = []

def call_gemini():
    payload = {
        "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "contents": history,
        "tools": TOOLS_DECL,
        "generationConfig": {"temperature": 0.7}
    }
    r = requests.post(URL, json=payload, timeout=60)
    r.raise_for_status()
    return r.json()

console.print("[bold cyan]🚀 Fas AI तैयार है![/bold cyan] ('exit' लिखो बंद करने के लिए)\n")

while True:
    try:
        user_input = console.input("[bold green]तुम > [/bold green]").strip()
        if not user_input:
            continue
        if user_input.lower() in ["exit", "quit"]:
            break

        history.append({"role": "user", "parts": [{"text": user_input}]})

        while True:
            with console.status("[cyan]Fas AI सोच रहा है...[/cyan]"):
                data = call_gemini()

            candidate = data["candidates"][0]
            content = candidate["content"]
            history.append(content)

            parts = content.get("parts", [])
            fn_calls = [p["functionCall"] for p in parts if "functionCall" in p]
            texts = [p["text"] for p in parts if "text" in p]

            if fn_calls:
                fn_responses = []
                for fc in fn_calls:
                    name = fc["name"]
                    args = fc.get("args", {})
                    if name == "run_shell":
                        res = run_shell(args.get("command", ""))
                    elif name == "write_file":
                        res = write_file(args.get("filepath", ""), args.get("content", ""))
                    elif name == "read_file":
                        res = read_file(args.get("filepath", ""))
                    else:
                        res = f"Unknown tool {name}"
                    fn_responses.append({
                        "functionResponse": {"name": name, "response": {"result": res}}
                    })
                history.append({"role": "user", "parts": fn_responses})
            else:
                console.print("\n[bold magenta]Fas AI >[/bold magenta]")
                console.print(Markdown("\n".join(texts)))
                console.print()
                break

    except KeyboardInterrupt:
        break
    except Exception as e:
        console.print(f"[bold red]Error:[/bold red] {e}\n")
