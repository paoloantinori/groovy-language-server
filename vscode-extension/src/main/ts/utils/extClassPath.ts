import * as fs from "fs";
import * as vscode from "vscode";

export default function getAdditionalClasspathFolder(): string | null {
	let extClassPathFolder: string | null = vscode.workspace.getConfiguration("groovy").get("additional.libraries");
	if (extClassPathFolder) {
		if (fs.existsSync(extClassPathFolder) && fs.statSync(extClassPathFolder).isDirectory()) {
			return extClassPathFolder;
		}
	}
	return null;
}
