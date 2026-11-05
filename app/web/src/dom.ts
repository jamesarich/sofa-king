// A small element builder, so the views read as the markup they make.

export type Child = Node | string | number | null | undefined | false;

export interface Props {
    class?: string;
    text?: string;
    attrs?: Record<string, string | number | boolean | undefined>;
    on?: { [K in keyof HTMLElementEventMap]?: (event: HTMLElementEventMap[K]) => void };
}

export function h<K extends keyof HTMLElementTagNameMap>(
    tag: K,
    props: Props = {},
    ...children: Child[]
): HTMLElementTagNameMap[K] {
    const element = document.createElement(tag);
    if (props.class) element.className = props.class;
    if (props.text !== undefined) element.textContent = props.text;
    for (const [name, value] of Object.entries(props.attrs ?? {})) {
        if (value === undefined || value === false) continue;
        element.setAttribute(name, value === true ? '' : String(value));
    }
    for (const [name, handler] of Object.entries(props.on ?? {})) {
        element.addEventListener(name, handler as EventListener);
    }
    append(element, children);
    return element;
}

export function append(parent: Node, children: Child[]): void {
    for (const child of children) {
        if (child === null || child === undefined || child === false) continue;
        parent.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
    }
}

export function replaceChildren(parent: Element, ...children: Child[]): void {
    parent.replaceChildren();
    append(parent, children);
}

/** A button that takes Enter and Space as a button does, and focus by arrow keys (see focus.ts). */
export function button(label: string, onClick: () => void, className = ''): HTMLButtonElement {
    return h('button', { class: className, text: label, attrs: { type: 'button' }, on: { click: onClick } });
}
