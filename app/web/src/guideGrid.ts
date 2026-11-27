import type { WebChannel, WebProgram } from '@sofaking/core';
import { button, h } from './dom';
import { clock, GRID_HOURS, gridCells, gridPx, gridSlots, gridStart, programWhen } from './guide';
import { href } from './router';

/** What the grid asks of the page that holds it. */
export interface GridActions {
    toggleFavorite(channelId: string): void;
    /** Opens the details of a program that is not on yet. */
    details(channel: WebChannel, program: WebProgram): void;
}

const HOUR_MS = 3_600_000;

/**
 * The guide as a grid at [at]: a row of times, then each channel with its programs on one timeline, from the half
 * hour now is in to six hours ahead. A program on now is a link to its channel; a later one opens its details.
 */
export function guideGrid(channels: readonly WebChannel[], favorites: readonly string[], at: number, actions: GridActions): HTMLElement {
    const start = gridStart(at);
    const end = start + GRID_HOURS * HOUR_MS;
    const width = { width: `${gridPx(end - start)}px` };
    const nowLine = () => h('span', { class: 'grid-now', style: { left: `${gridPx(at - start)}px` }, attrs: { 'aria-hidden': 'true' } });
    const head = h(
        'div',
        { class: 'grid-row grid-head', attrs: { 'aria-hidden': 'true' } },
        h('div', { class: 'grid-channel' }),
        h('div', { class: 'grid-track', style: width }, ...gridSlots(start).map((slot) => h('span', { class: 'grid-time', text: clock(slot), style: { left: `${gridPx(slot - start)}px` } }))),
    );
    return h('div', { class: 'grid-guide' }, head, ...channels.map((channel) => row(channel, favorites.includes(channel.id), start, end, at, actions, width, nowLine)));
}

function row(
    channel: WebChannel,
    favorite: boolean,
    start: number,
    end: number,
    at: number,
    actions: GridActions,
    width: Record<string, string>,
    nowLine: () => HTMLElement,
): HTMLElement {
    const star = h('button', {
        class: favorite ? 'star on' : 'star',
        text: favorite ? '★' : '☆',
        attrs: {
            type: 'button',
            'aria-label': `${favorite ? 'Remove' : 'Add'} ${channel.name} ${favorite ? 'from' : 'to'} favorites`,
            'aria-pressed': favorite,
            'data-key': `star:${channel.id}`,
        },
        on: { click: () => actions.toggleFavorite(channel.id) },
    });
    const name = h(
        'a',
        { class: 'grid-name', attrs: { href: href({ page: 'watch', id: channel.id }), 'data-key': `ch:${channel.id}`, 'aria-label': `Watch ${channel.number} ${channel.name}` } },
        channel.logo ? h('img', { class: 'logo', attrs: { src: channel.logo, alt: '', loading: 'lazy' } }) : null,
        h('span', { class: 'number', text: String(channel.number) }),
        h('span', { class: 'name', text: channel.name }),
    );
    const cells = gridCells(channel.programs, start, end).map((cell) => {
        const place = { left: `${gridPx(cell.startMs - start)}px`, width: `${gridPx(cell.stopMs - cell.startMs)}px` };
        const program = cell.program;
        if (!program) return h('span', { class: 'grid-cell gap', text: 'Nothing listed', style: place });
        const key = `prog:${channel.id}:${program.startMs}`;
        const label = [h('span', { class: 'cell-title', text: program.title }), h('span', { class: 'cell-time', text: clock(program.startMs) })];
        if (program.startMs <= at && program.stopMs > at) {
            return h('a', { class: 'grid-cell on', style: place, attrs: { href: href({ page: 'watch', id: channel.id }), 'data-key': key, title: program.title } }, ...label);
        }
        return h('button', { class: 'grid-cell', style: place, attrs: { type: 'button', 'data-key': key, title: program.title }, on: { click: () => actions.details(channel, program) } }, ...label);
    });
    return h('div', { class: 'grid-row' }, h('div', { class: 'grid-channel' }, name, star), h('div', { class: 'grid-track', style: width }, ...cells, nowLine()));
}

/** A later program's details: what it is, when and where it airs, and the way to its channel. */
export function programDetails(channel: WebChannel, program: WebProgram, onClose: () => void): HTMLElement {
    const facts = [program.subTitle, program.episode, program.isNew ? 'New' : '', program.rating].filter(Boolean).join(' · ');
    const close = button('Close', onClose);
    close.dataset.key = 'details:close';
    return h(
        'section',
        { class: 'program-details', attrs: { 'aria-label': `${program.title} details` } },
        h('h2', { text: program.title }),
        h('p', { class: 'facts', text: programWhen(program, channel) }),
        facts ? h('p', { class: 'facts', text: facts }) : null,
        program.description ? h('p', { class: 'overview', text: program.description }) : null,
        h(
            'div',
            { class: 'actions' },
            h('a', { class: 'button', text: `Watch ${channel.name} now`, attrs: { href: href({ page: 'watch', id: channel.id }), 'data-key': 'details:watch' } }),
            close,
        ),
    );
}
