"""Export one Dispatcharr channel profile as sofa-dispatcharr-export/1 JSON.

Runs inside the Dispatcharr container through Django's shell, which exec()s this
file (so there is no __main__ guard) and reads only from the database:

    docker exec -i -e SOFA_PROFILE=sofaking -e SOFA_OUT=/tmp/sofaking.json \
        dispatcharr python manage.py shell < scripts/dispatcharr-export.py
    docker cp dispatcharr:/tmp/sofaking.json ./sofaking.json

The output holds every stream URL of the profile, tokens included. Keep it out
of version control and delete it after `scripts/dispatcharr-import.py` has run.
Standard output carries counts only.
"""
import json
import os
from datetime import datetime, timezone

from apps.channels.models import ChannelProfile, ChannelProfileMembership, ChannelStream
from apps.epg.models import EPGSource

PROFILE = os.environ.get('SOFA_PROFILE', 'sofaking')
OUT = os.environ.get('SOFA_OUT', '/tmp/%s-export.json' % PROFILE)


def number(value):
    """Dispatcharr stores channel numbers as floats; keep `5` as `5` and `5.1` as `5.1`."""
    if value is None:
        return ''
    if float(value) == int(value):
        return str(int(value))
    return ('%.4f' % float(value)).rstrip('0').rstrip('.')


def logo_url(logo):
    url = getattr(logo, 'url', None) if logo is not None else None
    return url if url and url.startswith(('http://', 'https://')) else None


def effective(channel, field):
    """Household edits to auto-synced channels live on ChannelOverride; a non-null override wins."""
    override = getattr(channel, 'override', None)
    value = getattr(override, field, None) if override is not None else None
    return value if value is not None else getattr(channel, field)


profile = ChannelProfile.objects.get(name=PROFILE)
memberships = (
    ChannelProfileMembership.objects.filter(channel_profile=profile)
    .select_related('channel', 'channel__channel_group', 'channel__logo', 'channel__epg_data', 'channel__epg_data__epg_source')
    .order_by('channel__channel_number', 'channel__name')
)
channels = []
epg_source_ids = set()
for membership in memberships:
    channel = membership.channel
    epg = effective(channel, 'epg_data')
    group = effective(channel, 'channel_group')
    if epg is not None and epg.epg_source_id is not None:
        epg_source_ids.add(epg.epg_source_id)
    streams = []
    for link in ChannelStream.objects.filter(channel=channel).select_related('stream', 'stream__m3u_account').order_by('order', 'id'):
        stream = link.stream
        if not stream.url:
            continue
        account = getattr(stream, 'm3u_account', None)
        streams.append({'url': stream.url, 'name': stream.name, 'provider': account.name if account is not None else None})
    channels.append({
        'uuid': str(channel.uuid),
        'number': number(effective(channel, 'channel_number')),
        'name': effective(channel, 'name'),
        'group': group.name if group is not None else None,
        'logo': logo_url(effective(channel, 'logo')),
        'tvgId': effective(channel, 'tvg_id'),
        'epgSourceId': epg.epg_source_id if epg is not None else None,
        'epgChannelId': epg.tvg_id if epg is not None else None,
        'enabled': bool(membership.enabled) and not bool(getattr(channel, 'hidden_from_output', False)),
        'streams': streams,
    })

sources = [
    {'id': source.id, 'name': source.name, 'url': source.url}
    for source in EPGSource.objects.filter(id__in=epg_source_ids).order_by('id')
]
export = {
    'format': 'sofa-dispatcharr-export/1',
    'profile': PROFILE,
    'exportedAt': datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z'),
    'epgSources': sources,
    'channels': channels,
}
with open(OUT, 'w') as handle:
    json.dump(export, handle, indent=1)
print('exported profile=%s channels=%d streams=%d epgSources=%d to %s' % (
    PROFILE, len(channels), sum(len(c['streams']) for c in channels), len(sources), OUT))
