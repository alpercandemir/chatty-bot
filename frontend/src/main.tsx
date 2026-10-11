import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
    ArrowUpRight,
    ArrowUp,
    Check,
    ChevronDown,
    ChevronRight,
    Circle,
    CircleCheck,
    Clock3,
    Code2,
    Compass,
    Database,
    ExternalLink,
    FileText,
    Layers3,
    Leaf,
    LoaderCircle,
    MessageCircle,
    MoreHorizontal,
    Package,
    Play,
    Plus,
    RotateCcw,
    ShieldCheck,
    Sparkles,
    Square,
    Terminal,
    TriangleAlert,
    X,
} from 'lucide-react';
import './style.css';
type J = any;
async function api(path: string, body?: J): Promise<J> {
    const res = await fetch(path, {
        method: body === undefined ? 'GET' : 'POST',
        credentials: 'same-origin',
        headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) {
        throw new Error(data.error?.message || `Request failed (${res.status})`);
    }
    return data;
}
const base = '/api/v1';
const saved = () => {
    try {
        return JSON.parse(localStorage.getItem('chatty-current') || 'null');
    } catch {
        return null;
    }
};
function App() {
    const previous = useRef(saved());
    const [pack, setPack] = useState(previous.current?.pack || 'commerce');
    const [principal, setPrincipal] = useState(previous.current?.principal || 'customer-aylin');
    const [mode, setMode] = useState(previous.current?.mode || 'scripted');
    const [conv, setConv] = useState<J>(null);
    const [config, setConfig] = useState<J>(null);
    const [state, setState] = useState<J>(null);
    const [scenarios, setScenarios] = useState<J[]>([]);
    const [input, setInput] = useState('');
    const [error, setError] = useState('');
    const [busy, setBusy] = useState(false);
    const [panel, setPanel] = useState(true);
    const [tab, setTab] = useState('flow');
    const [selected, setSelected] = useState('');
    const [journeyStep, setJourneyStep] = useState(0);
    const [expanded, setExpanded] = useState<number | null>(null);
    const [historyRun, setHistoryRun] = useState<J>(null);
    const bottom = useRef<HTMLDivElement>(null);
    const source = useRef<EventSource | null>(null);
    const init = useRef(false);
    const active = conv?.run && ['QUEUED', 'RUNNING'].includes(conv.run.status);
    const run = historyRun || conv?.run;
    const action = conv?.action;
    const proposal = action?.status === 'PROPOSED';
    const brand = pack === 'commerce' ? 'Northstar Shop' : 'ClearSpace Cleaning';
    const refreshState = async (p = pack) => setState(await api(`${base}/demo/state?pack=${p}`));
    const restore = async (id: string) => {
        const c = await api(`${base}/conversations/${id}`);
        setConv(c);
        return c;
    };
    const connect = (rid: string, cid: string) => {
        source.current?.close();
        const ev = new EventSource(`${base}/runs/${rid}/events`);
        source.current = ev;
        ev.addEventListener('run-event', () => {
            void restore(cid);
        });
        ev.addEventListener('snapshot', (e) => {
            setConv(JSON.parse((e as MessageEvent).data));
            ev.close();
            void refreshState();
        });
        ev.onerror = () => {
            ev.close();
            void restore(cid)
                .then((c) => {
                    if (['QUEUED', 'RUNNING'].includes(c.run?.status)) {
                        setTimeout(() => connect(rid, cid), 1000);
                    } else {
                        void refreshState();
                    }
                })
                .catch((e) => setError(e.message));
        };
    };
    const begin = async (p: string, who: string, m: string, reset = false) => {
        source.current?.close();
        setBusy(true);
        setError('');
        setHistoryRun(null);
        try {
            if (reset) {
                await api(`${base}/demo/reset`, { pack: p });
            }
            await api(`${base}/demo/sessions`, { pack: p, principalId: who });
            const c = await api(`${base}/conversations`, { mode: m });
            setPack(p);
            setPrincipal(who);
            setMode(m);
            setConv(c);
            localStorage.setItem(
                'chatty-current',
                JSON.stringify({ id: c.id, pack: p, principal: who, mode: m }),
            );
            await refreshState(p);
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setBusy(false);
        }
    };
    useEffect(() => {
        if (init.current) {
            return;
        }
        init.current = true;
        void (async () => {
            try {
                const settings = await api(`${base}/demo/presenter`, {});
                setConfig(settings);
                setScenarios((await api(`${base}/demo/scenarios`)).scenarios);
                const p = previous.current;
                if (p) {
                    try {
                        const c = await restore(p.id);
                        await refreshState(p.pack);
                        if (['QUEUED', 'RUNNING'].includes(c.run?.status)) {
                            connect(c.run.id, c.id);
                        }
                        return;
                    } catch {
                        /* Reset or expired session: establish a fresh conversation. */
                    }
                }
                await begin(pack, principal, settings.defaultMode);
            } catch (e) {
                setError((e as Error).message);
            }
        })();
        return () => source.current?.close();
    }, []);
    useEffect(() => {
        bottom.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }, [conv?.messages?.length, proposal]);
    const send = async (text = input) => {
        if (!text.trim() || !conv || busy || active) {
            return;
        }
        setBusy(true);
        setError('');
        setHistoryRun(null);
        setInput('');
        try {
            const r = await api(`${base}/conversations/${conv.id}/messages`, {
                text,
                clientMessageId: crypto.randomUUID(),
            });
            await restore(conv.id);
            connect(r.runId, conv.id);
        } catch (e) {
            setInput(text);
            setError((e as Error).message);
        } finally {
            setBusy(false);
        }
    };
    const decide = async (confirm: boolean) => {
        if (!action) {
            return;
        }
        setBusy(true);
        setError('');
        try {
            await api(`${base}/actions/${action.id}/${confirm ? 'confirm' : 'decline'}`, {});
            await restore(conv.id);
            await refreshState();
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setBusy(false);
        }
    };
    const control = async (path: string, body: J = {}) => {
        setError('');
        try {
            await api(`${base}/demo/${path}`, body);
            await refreshState();
            if (conv) {
                await restore(conv.id);
            }
        } catch (e) {
            setError((e as Error).message);
        }
    };
    const starters =
        pack === 'commerce'
            ? [
                  ['Track a delivery', 'Where is order NS-1002?'],
                  ['Change an address', 'Change the delivery address for NS-1001.'],
                  ['Cancel an order', 'Cancel order NS-1001.'],
                  ['Explore our returns policy', 'What is your return policy?'],
              ]
            : [
                  ['Explore service areas', 'Do you clean offices in Atasehir?'],
                  [
                      'Request office cleaning',
                      'I need weekly cleaning for a 250 square meter office in Atasehir. Please contact office@example.invalid next weekday afternoon.',
                  ],
                  ['Explore the CRM', 'What does your cleaning CRM do?'],
                  [
                      'Request a CRM callback',
                      'I run a cleaning company with 12 employees. I need help scheduling jobs and assigning employees. Can I request a CRM demo callback by email at crm@example.invalid next weekday morning?',
                  ],
              ];
    const journey = scenarios.find((s) => s.id === selected);
    const nextStep = async () => {
        const step = journey?.steps[journeyStep];
        if (!step) {
            return;
        }
        if (step.message) {
            await send(step.message);
        } else if (step.uiAction) {
            if (step.uiAction.includes('DECLINE')) {
                await decide(false);
            } else {
                await decide(true);
            }
        } else if (step.presenterAction === 'ARM_FAULT') {
            await control('faults', { ...step, pack });
        } else if (step.presenterAction === 'ADVANCE_ORDER_TO_SHIPPED') {
            await control(`orders/${step.orderId}/advance`);
        } else if (step.presenterAction === 'RECONCILE_CURRENT_OPERATION' && action) {
            await control(`operations/${action.operationId}/reconcile?pack=${pack}`);
        }
        setJourneyStep((s) => s + 1);
    };
    return (
        <div className="app-shell">
            <aside className="sidebar">
                <a className="logo" href="/" aria-label="Chatty home">
                    <span className="logo-symbol">
                        <MessageCircle size={23} />
                        <i />
                    </span>
                    chatty<span className="logo-dot">.</span>
                </a>
                <div className="workspace-label">
                    YOUR WORKSPACE <span>LOCAL</span>
                </div>
                <button className="workspace" onClick={() => setTab('demo')}>
                    <span className="workspace-icon">
                        <Layers3 size={18} />
                    </span>
                    <span>
                        Demo studio<small>Service assistant</small>
                    </span>
                    <ChevronDown size={15} />
                </button>
                <nav>
                    <button className="nav-active" onClick={() => setHistoryRun(null)}>
                        <MessageCircle size={18} /> Playground <span className="nav-pill">2</span>
                    </button>
                    <button
                        onClick={() => {
                            setPanel(true);
                            setTab('history');
                        }}
                    >
                        <Clock3 size={18} /> Run history
                    </button>
                    <button
                        onClick={() => {
                            setPanel(true);
                            setTab('state');
                        }}
                    >
                        <Database size={18} /> Business data
                    </button>
                    <button
                        onClick={() => {
                            setPanel(true);
                            setTab('demo');
                        }}
                    >
                        <Compass size={18} /> Demo journeys
                    </button>
                </nav>
                <div className="side-divider" />
                <div className="workspace-label">SCENARIO PACKS</div>
                <button
                    className={`pack-button ${pack === 'commerce' ? 'chosen' : ''}`}
                    onClick={() => void begin('commerce', 'customer-aylin', mode)}
                    disabled={busy || active}
                >
                    <Package size={18} />
                    <span>
                        Commerce<small>Northstar Shop</small>
                    </span>
                    <span className="pack-dot" />
                </button>
                <button
                    className={`pack-button ${pack === 'cleaning' ? 'chosen' : ''}`}
                    onClick={() => void begin('cleaning', 'prospect-office', mode)}
                    disabled={busy || active}
                >
                    <Leaf size={18} />
                    <span>
                        Cleaning services<small>ClearSpace Cleaning</small>
                    </span>
                    <span className="pack-dot" />
                </button>
                <div className="sidebar-bottom">
                    <div className="sandbox-card">
                        <ShieldCheck size={20} />
                        <strong>A safe space to explore</strong>
                        <p>
                            Real workflows. Synthetic data.
                            <br />
                            Every change needs your say.
                        </p>
                        <button
                            onClick={() => void begin(pack, principal, mode, true)}
                            disabled={busy || active}
                        >
                            <RotateCcw size={14} /> Reset demo data
                        </button>
                    </div>
                    <div className="operator">
                        <div className="avatar">AD</div>
                        <div>
                            Demo operator<small>Local workspace</small>
                        </div>
                        <MoreHorizontal size={18} />
                    </div>
                </div>
            </aside>
            <main className="main">
                <header className="topbar">
                    <div className="breadcrumbs">
                        Demo studio <ChevronRight size={13} />
                        <strong>Playground</strong>
                    </div>
                    <div className="top-right">
                        <span className="environment">
                            <span /> Demo environment
                        </span>
                        <a
                            href={`http://${window.location.hostname}:8080/swagger-ui.html`}
                            target="_blank"
                            rel="noreferrer"
                        >
                            <Code2 size={16} /> API docs <ExternalLink size={12} />
                        </a>
                    </div>
                </header>
                <div className="page-heading">
                    <div>
                        <div className="eyebrow">THE ASSISTANT, IN ACTION</div>
                        <h1>
                            Good conversations.
                            <br className="mobile-br" /> Real outcomes<span>.</span>
                        </h1>
                        <p>Try a customer journey, then see how the assistant gets there.</p>
                    </div>
                    <button
                        className={`presenter-toggle ${panel ? 'on' : ''}`}
                        onClick={() => setPanel(!panel)}
                    >
                        <Terminal size={15} /> Presenter mode{' '}
                        <span className="switch">
                            <i />
                        </span>
                    </button>
                </div>
                <section className="controls">
                    <label>
                        <span>Scenario</span>
                        <select
                            value={pack}
                            disabled={busy || active}
                            onChange={(e) =>
                                void begin(
                                    e.target.value,
                                    e.target.value === 'commerce'
                                        ? 'customer-aylin'
                                        : 'prospect-office',
                                    mode,
                                )
                            }
                        >
                            <option value="commerce">Northstar Shop · Commerce</option>
                            <option value="cleaning">ClearSpace · Cleaning</option>
                        </select>
                    </label>
                    <div className="control-line" />
                    <label>
                        <span>Demo customer</span>
                        <select
                            value={principal}
                            disabled={busy || active}
                            onChange={(e) => void begin(pack, e.target.value, mode)}
                        >
                            {(config?.principals || [])
                                .filter(
                                    (p: J) =>
                                        p.workspaceId ===
                                        (pack === 'commerce'
                                            ? 'demo-northstar'
                                            : 'demo-clearspace'),
                                )
                                .map((p: J) => (
                                    <option key={p.id} value={p.id}>
                                        {p.displayName}
                                    </option>
                                ))}
                        </select>
                    </label>
                    <div className="mode-picker">
                        <button
                            className={mode === 'scripted' ? 'selected' : ''}
                            disabled={busy || active}
                            onClick={() => void begin(pack, principal, 'scripted')}
                        >
                            <Play size={13} /> Scripted rehearsal
                        </button>
                        <button
                            className={mode === 'live' ? 'selected' : ''}
                            disabled={busy || active}
                            onClick={() => void begin(pack, principal, 'live')}
                        >
                            <Sparkles size={13} /> Live AI
                        </button>
                    </div>
                </section>
                <div className={`studio ${panel ? 'with-panel' : ''}`}>
                    <section className="chat-card">
                        <div className="chat-header">
                            <div className="brand-mark">
                                {pack === 'commerce' ? <Package size={24} /> : <Leaf size={24} />}
                            </div>
                            <div>
                                <h2>{brand}</h2>
                                <span>
                                    <i /> Service assistant
                                </span>
                            </div>
                            <button
                                className="icon-button"
                                title="Start a new conversation"
                                aria-label="Start a new conversation"
                                disabled={busy || active}
                                onClick={() => void begin(pack, principal, mode)}
                            >
                                <Plus size={19} />
                            </button>
                        </div>
                        <div className="simulated-strip">
                            <ShieldCheck size={13} /> Demo environment — simulated business systems
                        </div>
                        <div className="messages" aria-live="polite">
                            {!conv?.messages?.length ? (
                                <div className="welcome">
                                    <div className="welcome-icon">
                                        <Sparkles size={27} />
                                    </div>
                                    <div className="eyebrow">A LITTLE HELP GOES A LONG WAY</div>
                                    <h3>Hi there. How can I help?</h3>
                                    <p>
                                        {pack === 'commerce'
                                            ? 'Let’s check on an order, make a change, or find an answer.'
                                            : 'Let’s find the right cleaning service or explore our CRM.'}
                                    </p>
                                    <div className="starters">
                                        {starters.map(([label, prompt], i) => (
                                            <button
                                                key={label}
                                                disabled={busy || active}
                                                onClick={() => void send(prompt)}
                                            >
                                                <span className="starter-icon">
                                                    {i === 0 ? (
                                                        <Package size={16} />
                                                    ) : i === 1 ? (
                                                        <ArrowUpRight size={16} />
                                                    ) : i === 2 ? (
                                                        <RotateCcw size={16} />
                                                    ) : (
                                                        <FileText size={16} />
                                                    )}
                                                </span>
                                                {label}
                                                <ArrowUpRight size={14} />
                                            </button>
                                        ))}
                                    </div>
                                    <div className="welcome-note">
                                        {mode === 'live' ? (
                                            <>
                                                <Sparkles size={13} /> Ask naturally. Gemini uses
                                                the business records to help.
                                            </>
                                        ) : (
                                            <>
                                                <Play size={13} /> Scripted demo: use the sample
                                                prompts, or switch to Live AI.
                                            </>
                                        )}
                                    </div>
                                </div>
                            ) : (
                                <div className="thread">
                                    {conv.messages.map((m: J) => (
                                        <div className={`message ${m.role}`} key={m.id}>
                                            {m.role === 'assistant' && (
                                                <div className="assistant-avatar">
                                                    <Sparkles size={14} />
                                                </div>
                                            )}
                                            <div>
                                                <div className="message-meta">
                                                    {m.role === 'user' ? 'You' : brand}
                                                </div>
                                                <div className="bubble">{m.text}</div>
                                                {m.sources?.map((s: J) => (
                                                    <details className="message-source" key={s.id}>
                                                        <summary>
                                                            <FileText size={12} />
                                                            {s.title} · v{s.version}
                                                        </summary>
                                                        <p>{s.content}</p>
                                                        <code>{s.id}</code>
                                                    </details>
                                                ))}
                                            </div>
                                        </div>
                                    ))}
                                    {(conv.run?.outputs || [])
                                        .flatMap((o: J) => o.result.shipments || [])
                                        .map((s: J) => (
                                            <div className="shipment-card" key={s.id}>
                                                <Package size={18} />
                                                <div>
                                                    <strong>{s.status.replaceAll('_', ' ')}</strong>
                                                    <p>
                                                        {s.carrier || 'Awaiting carrier'} ·{' '}
                                                        {s.trackingReference || s.id}
                                                    </p>
                                                    <small>
                                                        {s.estimatedDeliveryAt
                                                            ? `Estimated ${new Date(s.estimatedDeliveryAt).toLocaleString('en-GB', { timeZone: 'Europe/Istanbul' })} (Istanbul)`
                                                            : 'Delivery estimate unavailable'}
                                                    </small>
                                                </div>
                                            </div>
                                        ))}
                                    {action && (
                                        <div className={`action-card ${proposal ? 'pending' : ''}`}>
                                            <div className="action-title">
                                                <ShieldCheck size={17} />
                                                <strong>
                                                    {proposal
                                                        ? 'Your confirmation is needed'
                                                        : action.status.replaceAll('_', ' ')}
                                                </strong>
                                                <span>{action.operationId.slice(0, 11)}</span>
                                            </div>
                                            <p>{action.title}</p>
                                            {action.before?.deliveryAddress &&
                                            action.type === 'prepare_address_change' ? (
                                                <div className="address-preview">
                                                    <div>
                                                        <small>CURRENT ADDRESS</small>
                                                        <Address
                                                            value={action.before.deliveryAddress}
                                                        />
                                                    </div>
                                                    <ArrowUpRight size={16} />
                                                    <div>
                                                        <small>PROPOSED ADDRESS</small>
                                                        <Address value={action.args.address} />
                                                    </div>
                                                </div>
                                            ) : action.type === 'prepare_cancellation' ? (
                                                <div className="cancel-preview">
                                                    <strong>{action.args.orderId}</strong>
                                                    {action.before?.items?.map((i: J) => (
                                                        <span key={i.sku}>
                                                            {i.quantity} × {i.name}
                                                        </span>
                                                    ))}
                                                </div>
                                            ) : (
                                                <div className="lead-preview">
                                                    {Object.entries(action.args)
                                                        .filter(
                                                            ([k]) => k !== 'conversationSummary',
                                                        )
                                                        .map(([k, v]) => (
                                                            <div key={k}>
                                                                <small>
                                                                    {k.replace(/([A-Z])/g, ' $1')}
                                                                </small>
                                                                <span>{String(v)}</span>
                                                            </div>
                                                        ))}
                                                    {action.args.conversationSummary && (
                                                        <details>
                                                            <summary>
                                                                Conversation summary to submit
                                                            </summary>
                                                            {action.args.conversationSummary.map(
                                                                (m: J, i: number) => (
                                                                    <p key={i}>
                                                                        {m.role}: {m.text}
                                                                    </p>
                                                                ),
                                                            )}
                                                        </details>
                                                    )}
                                                </div>
                                            )}
                                            {proposal ? (
                                                <>
                                                    <div className="action-buttons">
                                                        <button
                                                            className="primary"
                                                            onClick={() => void decide(true)}
                                                            disabled={busy}
                                                        >
                                                            <Check size={15} /> Confirm
                                                        </button>
                                                        <button
                                                            onClick={() => void decide(false)}
                                                            disabled={busy}
                                                        >
                                                            <X size={15} /> Keep unchanged
                                                        </button>
                                                    </div>
                                                    <small className="expires">
                                                        Expires{' '}
                                                        {new Date(
                                                            action.expiresAt,
                                                        ).toLocaleTimeString('en-GB', {
                                                            hour: '2-digit',
                                                            minute: '2-digit',
                                                        })}
                                                        . Only these exact details will be
                                                        submitted.
                                                    </small>
                                                </>
                                            ) : (
                                                <small className="expires">
                                                    {action.status === 'UNKNOWN'
                                                        ? 'Outcome pending verification. Ask for support or use presenter reconciliation.'
                                                        : action.code ||
                                                          `Operation ${action.operationId}`}
                                                </small>
                                            )}
                                        </div>
                                    )}
                                    {active && (
                                        <div className="progress">
                                            <LoaderCircle className="spin" size={14} />
                                            {conv.run?.status === 'QUEUED'
                                                ? 'Waiting for the current live request to finish…'
                                                : mode === 'live'
                                                  ? 'Gemini is working through your request…'
                                                  : 'Checking the simulated business records…'}
                                            <button
                                                onClick={() =>
                                                    void api(
                                                        `${base}/runs/${conv.run.id}/stop`,
                                                        {},
                                                    ).then(() => restore(conv.id))
                                                }
                                            >
                                                <Square size={11} /> Stop assistant
                                            </button>
                                        </div>
                                    )}
                                    <div ref={bottom} />
                                </div>
                            )}
                        </div>
                        {error && (
                            <div className="error" role="alert">
                                <TriangleAlert size={16} />
                                <span>{error}</span>
                                <button aria-label="Dismiss error" onClick={() => setError('')}>
                                    <X size={14} />
                                </button>
                            </div>
                        )}
                        <form
                            className="composer"
                            onSubmit={(e) => {
                                e.preventDefault();
                                void send();
                            }}
                        >
                            <label className="sr-only" htmlFor="message">
                                Message the assistant
                            </label>
                            <textarea
                                id="message"
                                rows={1}
                                placeholder={
                                    mode === 'live'
                                        ? 'Ask a question or tell me what you need…'
                                        : 'Try a sample prompt · Switch to Live AI for conversation'
                                }
                                value={input}
                                onChange={(e) => setInput(e.target.value)}
                                maxLength={4000}
                                onKeyDown={(e) => {
                                    if (e.key === 'Enter' && !e.shiftKey) {
                                        e.preventDefault();
                                        void send();
                                    }
                                }}
                            />
                            <button
                                aria-label="Send message"
                                disabled={!input.trim() || busy || active || !conv}
                            >
                                <ArrowUp size={19} />
                            </button>
                        </form>
                        <div className="chat-footer">
                            <span>
                                {mode === 'scripted' ? (
                                    <>
                                        <Play size={10} /> Scripted rehearsal · Sample prompts only
                                    </>
                                ) : (
                                    <>
                                        <Sparkles size={11} /> Live AI · {config?.model}
                                    </>
                                )}
                            </span>
                            <span>Powered by Chatty</span>
                        </div>
                    </section>
                    {panel && (
                        <aside className="presenter-panel">
                            <div className="panel-heading">
                                <span className="panel-icon">
                                    <Terminal size={16} />
                                </span>
                                <div>
                                    <h2>Behind the conversation</h2>
                                    <p>A clear view of every step.</p>
                                </div>
                                <span className="live-dot">EVENTS</span>
                            </div>
                            <div className="panel-tabs">
                                {[
                                    ['flow', 'Execution'],
                                    ['state', 'Business state'],
                                    ['demo', 'Journeys'],
                                    ['history', 'History'],
                                ].map(([id, label]) => (
                                    <button
                                        key={id}
                                        className={tab === id ? 'active' : ''}
                                        onClick={() => setTab(id)}
                                    >
                                        {label}
                                    </button>
                                ))}
                            </div>
                            <div className="panel-body">
                                {tab === 'flow' ? (
                                    <>
                                        <div className="flow-info">
                                            <span>
                                                <span className="tiny-dot" />
                                                {run
                                                    ? run.status.replaceAll('_', ' ')
                                                    : 'Ready for a conversation'}
                                            </span>
                                            <small>
                                                {run
                                                    ? `${run.toolCalls} tools · ${run.activeMs || 0} ms`
                                                    : 'Waiting for your first message'}
                                            </small>
                                        </div>
                                        <div className="timeline">
                                            {!run ? (
                                                <>
                                                    {[
                                                        [
                                                            'Understand the request',
                                                            'Load the conversation and customer',
                                                        ],
                                                        [
                                                            'Gather the facts',
                                                            'Read authorized records and sources',
                                                        ],
                                                        [
                                                            'Check the policy',
                                                            'Validate the proposed business action',
                                                        ],
                                                        [
                                                            'Ask for confirmation',
                                                            'Let the customer review exact details',
                                                        ],
                                                        [
                                                            'Execute & verify',
                                                            'Persist the outcome, then report it',
                                                        ],
                                                    ].map(([title, desc], i) => (
                                                        <div
                                                            className="timeline-item dormant"
                                                            key={title}
                                                        >
                                                            <span className="timeline-node">
                                                                {i + 1}
                                                            </span>
                                                            <div>
                                                                <h4>{title}</h4>
                                                                <p>{desc}</p>
                                                            </div>
                                                        </div>
                                                    ))}
                                                </>
                                            ) : (
                                                run.events?.map((e: J, i: number) => (
                                                    <div
                                                        className={`timeline-item ${e.type === 'error' ? 'failed' : ''}`}
                                                        key={e.id}
                                                    >
                                                        <span className="timeline-node">
                                                            {e.type === 'error' ? (
                                                                <TriangleAlert size={13} />
                                                            ) : e.type === 'confirmation' ? (
                                                                <ShieldCheck size={13} />
                                                            ) : (
                                                                <Check size={13} />
                                                            )}
                                                        </span>
                                                        <div>
                                                            <button
                                                                className="event-toggle"
                                                                onClick={() =>
                                                                    setExpanded(
                                                                        expanded === i ? null : i,
                                                                    )
                                                                }
                                                            >
                                                                <h4>{e.title}</h4>
                                                                <ChevronDown size={13} />
                                                            </button>
                                                            <p>
                                                                {new Date(e.at).toLocaleTimeString(
                                                                    'en-GB',
                                                                )}{' '}
                                                                · {e.type}
                                                            </p>
                                                            {expanded === i && (
                                                                <pre>
                                                                    {JSON.stringify(
                                                                        e.detail,
                                                                        null,
                                                                        2,
                                                                    )}
                                                                </pre>
                                                            )}
                                                        </div>
                                                    </div>
                                                ))
                                            )}
                                        </div>
                                        <div className="policy-note">
                                            <ShieldCheck size={19} />
                                            <div>
                                                <strong>Permission is part of the process.</strong>
                                                <p>
                                                    The assistant can propose a change. Only your
                                                    exact confirmation can execute it.
                                                </p>
                                            </div>
                                        </div>
                                        <div className="run-meta">
                                            <div>
                                                <span>Scenario pack</span>
                                                <strong>
                                                    {pack} <small>v1.0.0</small>
                                                </strong>
                                            </div>
                                            <div>
                                                <span>Model mode</span>
                                                <strong>
                                                    {mode === 'scripted'
                                                        ? 'Scripted rehearsal'
                                                        : 'Live Gemini'}
                                                </strong>
                                            </div>
                                            <div>
                                                <span>Policy & tool schema</span>
                                                <strong>v1.0.0</strong>
                                            </div>
                                            <div>
                                                <span>Provider usage</span>
                                                <strong>
                                                    {run?.usage?.totalTokenCount
                                                        ? `${run.usage.totalTokenCount} tokens`
                                                        : 'Not reported'}
                                                </strong>
                                            </div>
                                            {run && (
                                                <div>
                                                    <span>Run reference</span>
                                                    <code>{run.id.slice(0, 19)}</code>
                                                </div>
                                            )}
                                        </div>
                                    </>
                                ) : tab === 'state' ? (
                                    <>
                                        <div className="section-label">
                                            PERSISTENT SIMULATOR RECORDS{' '}
                                            <button
                                                className="icon-button"
                                                title="Refresh business state"
                                                onClick={() => void refreshState()}
                                            >
                                                <RotateCcw size={13} />
                                            </button>
                                        </div>
                                        {state?.orders?.map((o: J) => (
                                            <details className="record" key={o.id}>
                                                <summary>
                                                    <Package size={15} />
                                                    <strong>{o.id}</strong>
                                                    <span
                                                        className={`status ${o.status === 'CANCELLED' ? 'muted' : ''}`}
                                                    >
                                                        {o.status}
                                                    </span>
                                                    <small>v{o.version}</small>
                                                </summary>
                                                <p>{o.items.map((i: J) => i.name).join(', ')}</p>
                                                <Address value={o.deliveryAddress} />
                                                <div className="record-footer">
                                                    <span>{o.refundStatus}</span>
                                                    <button
                                                        onClick={() =>
                                                            void control(`orders/${o.id}/advance`)
                                                        }
                                                    >
                                                        Advance to shipped
                                                    </button>
                                                </div>
                                            </details>
                                        ))}
                                        {['supportTickets', 'callbackRequests', 'actions'].map(
                                            (k) => (
                                                <div key={k}>
                                                    <h4 className="record-group">
                                                        {k.replace(/([A-Z])/g, ' $1')}{' '}
                                                        <span>{state?.[k]?.length || 0}</span>
                                                    </h4>
                                                    {state?.[k]?.map((r: J) => (
                                                        <details className="record" key={r.id}>
                                                            <summary>
                                                                <FileText size={14} />
                                                                <strong>{r.id.slice(0, 14)}</strong>
                                                                <span className="status">
                                                                    {r.status}
                                                                </span>
                                                            </summary>
                                                            <pre>{JSON.stringify(r, null, 2)}</pre>
                                                            {r.status === 'UNKNOWN' && (
                                                                <button
                                                                    className="primary"
                                                                    onClick={() =>
                                                                        void control(
                                                                            `operations/${r.operationId}/reconcile?pack=${pack}`,
                                                                        )
                                                                    }
                                                                >
                                                                    Reconcile authoritative outcome
                                                                </button>
                                                            )}
                                                        </details>
                                                    ))}
                                                </div>
                                            ),
                                        )}
                                    </>
                                ) : tab === 'history' ? (
                                    <>
                                        <div className="section-label">
                                            SAVED RUNS · {state?.runs?.length || 0}
                                        </div>
                                        {state?.runs
                                            ?.slice()
                                            .reverse()
                                            .map((r: J) => (
                                                <button
                                                    className="history-row"
                                                    key={r.id}
                                                    onClick={() => {
                                                        setHistoryRun(r);
                                                        setTab('flow');
                                                    }}
                                                >
                                                    <Clock3 size={16} />
                                                    <div>
                                                        <strong>{r.status}</strong>
                                                        <small>
                                                            {new Date(r.createdAt).toLocaleString(
                                                                'en-GB',
                                                            )}{' '}
                                                            · {r.mode}
                                                        </small>
                                                        <code>{r.id.slice(0, 20)}</code>
                                                    </div>
                                                    <ChevronRight size={14} />
                                                </button>
                                            ))}
                                        {!state?.runs?.length && (
                                            <p className="empty-hint">
                                                Your conversations will leave an inspectable trail
                                                here.
                                            </p>
                                        )}
                                    </>
                                ) : (
                                    <>
                                        <div className="section-label">
                                            16 REPEATABLE DEMO JOURNEYS
                                        </div>
                                        <label className="journey-selector">
                                            <span>Choose a journey</span>
                                            <select
                                                value={selected}
                                                onChange={(e) => {
                                                    setSelected(e.target.value);
                                                    setJourneyStep(0);
                                                }}
                                            >
                                                <option value="">Select a sample journey</option>
                                                {scenarios.map((s) => (
                                                    <option key={s.id} value={s.id}>
                                                        {s.id.replaceAll('-', ' ')}
                                                    </option>
                                                ))}
                                            </select>
                                        </label>
                                        {journey && (
                                            <div className="journey">
                                                <p>
                                                    Start with fresh fixtures, then walk through
                                                    each step. Every confirmation is explicit.
                                                </p>
                                                <button
                                                    className="primary"
                                                    disabled={active || busy}
                                                    onClick={() => {
                                                        setJourneyStep(0);
                                                        void begin(
                                                            journey.workspaceId === 'demo-northstar'
                                                                ? 'commerce'
                                                                : 'cleaning',
                                                            journey.principalId,
                                                            mode,
                                                            true,
                                                        );
                                                    }}
                                                >
                                                    <RotateCcw size={14} /> Prepare this journey
                                                </button>
                                                {journey.steps.map((s: J, i: number) => (
                                                    <div
                                                        className={`journey-step ${i < journeyStep ? 'done' : ''}`}
                                                        key={i}
                                                    >
                                                        <span>
                                                            {i < journeyStep ? (
                                                                <Check size={12} />
                                                            ) : (
                                                                i + 1
                                                            )}
                                                        </span>
                                                        {s.message ||
                                                            s.uiAction
                                                                ?.replaceAll('_', ' ')
                                                                .toLowerCase() ||
                                                            s.presenterAction
                                                                ?.replaceAll('_', ' ')
                                                                .toLowerCase()}
                                                    </div>
                                                ))}
                                                <button
                                                    className="primary"
                                                    disabled={
                                                        busy ||
                                                        active ||
                                                        journeyStep >= journey.steps.length ||
                                                        pack !==
                                                            (journey.workspaceId ===
                                                            'demo-northstar'
                                                                ? 'commerce'
                                                                : 'cleaning')
                                                    }
                                                    onClick={() => void nextStep()}
                                                >
                                                    <Play size={13} />
                                                    {journeyStep >= journey.steps.length
                                                        ? 'Journey complete'
                                                        : 'Run next step'}
                                                </button>
                                                <details>
                                                    <summary>Expected outcome</summary>
                                                    <pre>
                                                        {JSON.stringify(journey.expected, null, 2)}
                                                    </pre>
                                                </details>
                                            </div>
                                        )}
                                        <h4 className="record-group">Failure controls</h4>
                                        <p className="empty-hint">
                                            Apply to the next call for NS-1001 (writes) or NS-1002
                                            (reads).
                                        </p>
                                        {[
                                            [
                                                'READ_TIMEOUT',
                                                'Read unavailable (with retry)',
                                                'NS-1002',
                                                2,
                                            ],
                                            [
                                                'WRITE_FAILURE_BEFORE_COMMIT',
                                                'Write fails before commit',
                                                'NS-1001',
                                                1,
                                            ],
                                            [
                                                'WRITE_TIMEOUT_AFTER_COMMIT',
                                                'Response lost after commit',
                                                'NS-1001',
                                                1,
                                            ],
                                        ].map(([fault, label, orderId, occurrences]) => (
                                            <button
                                                className="fault-button"
                                                key={String(fault)}
                                                disabled={pack !== 'commerce'}
                                                onClick={() =>
                                                    void control('faults', {
                                                        fault,
                                                        orderId,
                                                        occurrences,
                                                        pack,
                                                    })
                                                }
                                            >
                                                <TriangleAlert size={14} />
                                                {label}
                                                <Plus size={13} />
                                            </button>
                                        ))}
                                    </>
                                )}
                            </div>
                            <div className="panel-footer">
                                <CircleCheck size={13} /> Application events. No hidden model
                                reasoning.
                            </div>
                        </aside>
                    )}
                </div>
                <footer className="page-footer">
                    <span>
                        <span className="tiny-dot" /> Local workspace · Synthetic data only
                    </span>
                    <span>Built for conversations that get things done.</span>
                </footer>
            </main>
        </div>
    );
}
function Address({ value }: { value: J }) {
    if (!value) {
        return null;
    }
    return (
        <p className="address">
            {value.line1}
            <br />
            {value.district}, {value.city} {value.postalCode}
            <br />
            {value.countryCode}
        </p>
    );
}
createRoot(document.getElementById('root')!).render(<App />);
