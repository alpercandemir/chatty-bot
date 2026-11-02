import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
    ArrowUpRight,
    ArrowUp,
    Check,
    ChevronDown,
    ChevronRight,
    CircleCheck,
    Clock3,
    Code2,
    ExternalLink,
    FileText,
    LoaderCircle,
    MessageCircle,
    Package,
    Play,
    Plus,
    RotateCcw,
    ShieldCheck,
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
    const pack = 'commerce';
    const [principal, setPrincipal] = useState(
        previous.current?.pack === 'commerce' ? previous.current.principal : 'customer-aylin',
    );
    const [mode, setMode] = useState(previous.current?.mode || 'live');
    const [conv, setConv] = useState<J>(null);
    const [config, setConfig] = useState<J>(null);
    const [state, setState] = useState<J>(null);
    const [scenarios, setScenarios] = useState<J[]>([]);
    const [input, setInput] = useState('');
    const [error, setError] = useState('');
    const [busy, setBusy] = useState(false);
    const [panel, setPanel] = useState(false);
    const [tab, setTab] = useState('flow');
    const [selected, setSelected] = useState('');
    const [journeyStep, setJourneyStep] = useState(0);
    const [expanded, setExpanded] = useState<number | null>(null);
    const [historyRun, setHistoryRun] = useState<J>(null);
    const bottom = useRef<HTMLDivElement>(null);
    const inspector = useRef<HTMLElement>(null);
    const source = useRef<EventSource | null>(null);
    const init = useRef(false);
    const active = conv?.run && ['QUEUED', 'RUNNING'].includes(conv.run.status);
    const run = historyRun || conv?.run;
    const action = conv?.action;
    const proposal = action?.status === 'PROPOSED';
    const brand = 'Northstar Shop';
    const customers: J[] = (config?.principals || []).filter(
        (p: J) => p.workspaceId === 'demo-northstar',
    );
    const customer = customers.find((p: J) => p.id === principal);
    const orders: J[] = (state?.orders || []).filter((o: J) => o.principalId === principal);
    const processing = orders.find((o: J) => o.status === 'PROCESSING' && !o.fulfillmentLocked);
    const shipped = orders.find((o: J) => o.shipmentIds.length > 0 && o.status !== 'DELIVERED');
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
        m = config?.liveOnly ? 'live' : m;
        source.current?.close();
        setBusy(true);
        setError('');
        setHistoryRun(null);
        setInput('');
        try {
            if (reset) {
                await api(`${base}/demo/reset`, { pack: p });
            }
            await api(`${base}/demo/sessions`, { pack: p, principalId: who });
            const c = await api(`${base}/conversations`, { mode: m });
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
                setScenarios(
                    (await api(`${base}/demo/scenarios`)).scenarios.filter(
                        (s: J) => s.workspaceId === 'demo-northstar',
                    ),
                );
                const p = previous.current;
                if (p?.pack === 'commerce' && (!settings.liveOnly || p.mode === 'live')) {
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
    useEffect(() => {
        if (panel) {
            inspector.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    }, [panel]);
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
    const starters = [
        ...(shipped ? [['Track an order', `Where is order ${shipped.id}?`]] : []),
        ...(processing
            ? [
                  ['Change an address', `Change the delivery address for ${processing.id}.`],
                  ['Cancel an order', `Cancel order ${processing.id}.`],
              ]
            : []),
        ['Return policy', 'What is your return policy?'],
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
            <header className="topbar">
                <a className="logo" href="/" aria-label="Chatty home">
                    chatty
                </a>
                <span className="header-divider" />
                <span className="demo-label">E-commerce demo</span>
                <div className="top-right">
                    <button
                        className={panel ? 'selected' : ''}
                        aria-expanded={panel}
                        aria-controls="demo-inspector"
                        onClick={() => setPanel(!panel)}
                    >
                        <Terminal size={15} /> Inspector
                    </button>
                    <a href="/swagger-ui.html" target="_blank" rel="noreferrer">
                        <Code2 size={15} /> API docs <ExternalLink size={11} />
                    </a>
                </div>
            </header>
            <main className="main">
                <div className="page-heading">
                    <div>
                        <h1>Customer support</h1>
                        <p>Track orders, update delivery details, or request a cancellation.</p>
                    </div>
                    <button
                        className="reset-button"
                        onClick={() => void begin(pack, principal, mode, true)}
                        disabled={busy || active}
                    >
                        <RotateCcw size={14} /> Reset demo
                    </button>
                </div>
                <div className="studio">
                    <section className="chat-card">
                        <div className="chat-header">
                            <div className="brand-mark">
                                <MessageCircle size={19} />
                            </div>
                            <div>
                                <h2>{brand}</h2>
                                <span>
                                    Chatting as {customer?.displayName?.replace(' Demo', '') || '…'}
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
                        <div className="messages" aria-live="polite">
                            {!conv?.messages?.length ? (
                                <div className="welcome">
                                    <h3>Ask about an order</h3>
                                    <p>Select a customer, then type a message or use an example.</p>
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
                                </div>
                            ) : (
                                <div className="thread">
                                    {conv.messages.map((m: J) => (
                                        <div className={`message ${m.role}`} key={m.id}>
                                            {m.role === 'assistant' && (
                                                <div className="assistant-avatar">
                                                    <MessageCircle size={14} />
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
                                        ? 'Message the assistant…'
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
                                        <MessageCircle size={11} /> Live AI
                                    </>
                                )}
                            </span>
                            <span>Changes require confirmation</span>
                        </div>
                    </section>
                    <aside className="customer-panel" aria-label="Demo customer details">
                        <div className="context-heading">
                            <h2>Demo customers</h2>
                            <span>{customers.length}</span>
                        </div>
                        <div className="customer-list">
                            {customers.map((p: J) => (
                                <button
                                    key={p.id}
                                    className={`customer-option ${principal === p.id ? 'selected' : ''}`}
                                    aria-pressed={principal === p.id}
                                    disabled={busy || active || !conv}
                                    onClick={() => void begin(pack, p.id, mode)}
                                >
                                    <span className="customer-initial">
                                        {p.displayName.charAt(0)}
                                    </span>
                                    <span>
                                        <strong>{p.displayName.replace(' Demo', '')}</strong>
                                        <small>{p.demoLabel}</small>
                                    </span>
                                    {principal === p.id && <Check size={14} />}
                                </button>
                            ))}
                        </div>
                        <div className="customer-summary">
                            <h3>
                                {customer?.displayName?.replace(' Demo', '') || 'Loading customer…'}
                            </h3>
                            <p>{customer?.email}</p>
                            <p className="customer-note">{customer?.demoSummary}</p>
                            <div className="section-label">
                                Orders <span>{orders.length}</span>
                            </div>
                            <CustomerOrders
                                orders={orders.slice(0, 3)}
                                shipments={state?.shipments || []}
                            />
                            {orders.length > 3 && (
                                <details className="more-orders">
                                    <summary>{orders.length - 3} more orders</summary>
                                    <CustomerOrders
                                        orders={orders.slice(3)}
                                        shipments={state?.shipments || []}
                                    />
                                </details>
                            )}
                        </div>
                        <div className="context-footer">
                            {config && !config.liveOnly && (
                                <label className="mode-control">
                                    Mode
                                    <select
                                        value={mode}
                                        disabled={busy || active}
                                        onChange={(e) =>
                                            void begin(pack, principal, e.target.value)
                                        }
                                    >
                                        <option value="live">Live AI</option>
                                        <option value="scripted">Scripted rehearsal</option>
                                    </select>
                                </label>
                            )}
                            <span>Switching customers starts a new chat.</span>
                        </div>
                    </aside>
                </div>
                {panel && (
                    <aside className="presenter-panel" id="demo-inspector" ref={inspector}>
                        <div className="panel-heading">
                            <span className="panel-icon">
                                <Terminal size={16} />
                            </span>
                            <div>
                                <h2>Inspector</h2>
                            </div>
                            <button
                                className="icon-button"
                                aria-label="Close inspector"
                                onClick={() => setPanel(false)}
                            >
                                <X size={16} />
                            </button>
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
                                            <p className="empty-hint">
                                                Send a message to see tool calls and results.
                                            </p>
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
                                                                {JSON.stringify(e.detail, null, 2)}
                                                            </pre>
                                                        )}
                                                    </div>
                                                </div>
                                            ))
                                        )}
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
                                    {['supportTickets', 'actions'].map((k) => (
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
                                                        <span className="status">{r.status}</span>
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
                                    ))}
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
                                        <p className="empty-hint">No saved runs yet.</p>
                                    )}
                                </>
                            ) : (
                                <>
                                    <div className="section-label">
                                        {scenarios.length} DEMO JOURNEYS
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
                                                Start with fresh fixtures, then walk through each
                                                step. Every confirmation is explicit.
                                            </p>
                                            <button
                                                className="primary"
                                                disabled={active || busy}
                                                onClick={() => {
                                                    setJourneyStep(0);
                                                    void begin(
                                                        pack,
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
                                                    principal !== journey.principalId
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
                            <CircleCheck size={13} /> Application events. No hidden model reasoning.
                        </div>
                    </aside>
                )}
                <footer className="page-footer">
                    Synthetic customer data · No real orders or payments
                </footer>
            </main>
        </div>
    );
}
function CustomerOrders({ orders, shipments }: { orders: J[]; shipments: J[] }) {
    return (
        <div className="customer-orders">
            {orders.map((o: J) => {
                const shipment =
                    shipments.find((s: J) => s.orderId === o.id && s.status === 'DELAYED') ||
                    shipments.find((s: J) => s.orderId === o.id);
                const status =
                    shipment?.status === 'DELAYED'
                        ? 'Delayed'
                        : o.status.toLowerCase().replaceAll('_', ' ');
                return (
                    <div className="order-summary" key={o.id}>
                        <div>
                            <strong>{o.id}</strong>
                            <span
                                className={`order-status ${o.status === 'CANCELLED' ? 'muted' : ''}`}
                            >
                                {status}
                            </span>
                        </div>
                        <p>{o.items.map((i: J) => i.name.replace('Demo ', '')).join(', ')}</p>
                        <small>
                            {o.deliveryAddress.district}, {o.deliveryAddress.city}
                        </small>
                        {o.status === 'PROCESSING' && (
                            <small>
                                {o.fulfillmentLocked
                                    ? 'Preparing for dispatch; changes locked.'
                                    : 'Address changes and cancellation available.'}
                            </small>
                        )}
                        {shipment?.status === 'DELAYED' && (
                            <small>Carrier delay; no revised delivery date.</small>
                        )}
                        {o.status === 'PARTIALLY_SHIPPED' && (
                            <small>One package in transit; one awaiting dispatch.</small>
                        )}
                        {o.status === 'CANCELLED' && <small>Refund follow-up pending.</small>}
                    </div>
                );
            })}
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
