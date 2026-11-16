import { readFileSync } from 'node:fs';

export function readWorkflow(name = 'ci.yml') {
  return readFileSync(new URL(`../../.github/workflows/${name}`, import.meta.url), 'utf8');
}

// Match only direct jobs children, never nested step IDs or reusable inputs.
export function workflowJobs(source) {
  const body = source.match(/^jobs:\s*\n([\s\S]*)/m)?.[1] ?? '';
  const headings = [...body.matchAll(/^  ([\w-]+):[^\S\n]*(?:#.*)?$/gm)];
  return Object.fromEntries(headings.map((heading, index) => [
    heading[1], body.slice(heading.index, headings[index + 1]?.index ?? body.length),
  ]));
}

export function workflowJob(source, name) {
  return workflowJobs(source)[name] ?? '';
}
