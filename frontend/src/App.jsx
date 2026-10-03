import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity, ArrowDownRight, ArrowRight, ArrowUpRight, Bell, Box, Check,
  ChevronDown, CircleHelp, Command, CreditCard, LayoutDashboard, LoaderCircle,
  Menu, Package, Plus, RefreshCw, Search, ShoppingBag, ShoppingCart, Sparkles,
  Truck, X,
} from 'lucide-react';
import {
  createOrder, getNotifications, getOrders, getPaymentsForOrder, getProducts,
  readableError,
} from './api.js';

const money = (value) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(value || 0));
const dateTime = (value) => value ? new Date(value).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '—';
const statusTone = (status = '') => {
  const normalized = status.toLowerCase();
  if (normalized.includes('confirm') || normalized.includes('success') || normalized === 'succeeded') return 'status-green';
  if (normalized.includes('fail') || normalized.includes('declin')) return 'status-red';
  if (normalized.includes('pending') || normalized.includes('process')) return 'status-amber';
  return 'status-slate';
};

const navItems = [
  { id: 'overview', label: 'Overview', icon: LayoutDashboard },
  { id: 'orders', label: 'Orders', icon: ShoppingBag },
  { id: 'inventory', label: 'Inventory', icon: Package },
  { id: 'payments', label: 'Payments', icon: CreditCard },
  { id: 'notifications', label: 'Notifications', icon: Bell },
];

function App() {
  const [active, setActive] = useState('overview');
  const [products, setProducts] = useState([]);
  const [orders, setOrders] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [payments, setPayments] = useState([]);
  const [busy, setBusy] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [toast, setToast] = useState(null);
  const [orderOpen, setOrderOpen] = useState(false);
  const [selectedOrder, setSelectedOrder] = useState(null);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  const loadData = useCallback(async (quiet = false) => {
    if (quiet) setRefreshing(true); else setBusy(true);
    setError('');
    try {
      const results = await Promise.allSettled([getProducts(), getOrders(), getNotifications()]);
      const [productsResult, ordersResult, noticesResult] = results;
      if (productsResult.status === 'fulfilled') setProducts(productsResult.value);
      if (ordersResult.status === 'fulfilled') setOrders(ordersResult.value);
      if (noticesResult.status === 'fulfilled') setNotifications(noticesResult.value);
      const failed = results.find((result) => result.status === 'rejected');
      if (failed) setError(readableError(failed.reason));
    } finally {
      setBusy(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);
  useEffect(() => {
    const navigate = (event) => setActive(event.detail);
    window.addEventListener('navigate', navigate);
    return () => window.removeEventListener('navigate', navigate);
  }, []);
  useEffect(() => {
    if (!toast) return undefined;
    const timeout = window.setTimeout(() => setToast(null), 4600);
    return () => window.clearTimeout(timeout);
  }, [toast]);

  const filteredOrders = useMemo(() => {
    const term = search.trim().toLowerCase();
    if (!term) return orders;
    return orders.filter((order) => [order.id, order.customerId, order.status].some((v) => String(v || '').toLowerCase().includes(term)));
  }, [orders, search]);

  const totalRevenue = orders.reduce((sum, order) => sum + (String(order.status).toUpperCase() === 'CONFIRMED' ? Number(order.totalAmount || 0) : 0), 0);
  const confirmedOrders = orders.filter((order) => String(order.status).toUpperCase() === 'CONFIRMED').length;
  const lowStock = products.filter((product) => Number(product.availableQuantity) <= 5).length;

  async function showOrder(order) {
    setSelectedOrder(order);
    setPayments([]);
    try {
      const data = await getPaymentsForOrder(order.id);
      setPayments(data);
    } catch {
      setPayments([]);
    }
  }

  function notify(message, kind = 'success') { setToast({ message, kind }); }

  const contentTitle = navItems.find((item) => item.id === active)?.label || 'Overview';

  return (
    <div className="app-shell">
      {mobileNavOpen && <button aria-label="Close navigation" className="mobile-scrim" onClick={() => setMobileNavOpen(false)} />}
      <aside className={`sidebar ${mobileNavOpen ? 'sidebar-open' : ''}`}>
        <div className="brand-row">
          <div className="brand-mark"><Command size={18} strokeWidth={2.5} /></div>
          <div><div className="brand-name">northstar</div><div className="brand-caption">COMMERCE OS</div></div>
          <button className="icon-button sidebar-close" onClick={() => setMobileNavOpen(false)} aria-label="Close menu"><X size={18} /></button>
        </div>
        <div className="workspace-switcher">
          <div className="workspace-avatar">N</div>
          <div className="min-w-0 flex-1"><div className="workspace-title">Northstar store</div><div className="workspace-subtitle">Operations workspace</div></div>
          <ChevronDown size={15} className="text-slate-400" />
        </div>
        <div className="nav-label">WORKSPACE</div>
        <nav className="side-nav" aria-label="Main navigation">
          {navItems.map(({ id, label, icon: Icon }) => (
            <button key={id} className={`nav-link ${active === id ? 'nav-link-active' : ''}`} onClick={() => { setActive(id); setMobileNavOpen(false); }}>
              <Icon size={18} strokeWidth={1.8} /><span>{label}</span>
              {id === 'orders' && orders.length > 0 && <span className="nav-count">{orders.length}</span>}
              {id === 'inventory' && lowStock > 0 && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="nav-label nav-label-spaced">SYSTEM</div>
        <div className="system-card">
          <span className={`system-pulse ${error ? 'pulse-offline' : ''}`} />
          <div className="min-w-0 flex-1"><div className="system-title">Gateway connection</div><div className="system-subtitle">{error ? 'Needs attention' : 'All systems operational'}</div></div>
          <span className={`system-indicator ${error ? 'indicator-offline' : ''}`} />
        </div>
        <div className="sidebar-bottom">
          <div className="help-card"><div className="help-icon"><CircleHelp size={17} /></div><div><div className="help-title">Need a hand?</div><div className="help-copy">Your workspace is ready.</div></div><ArrowRight size={15} className="ml-auto text-slate-400" /></div>
          <div className="profile-row"><div className="profile-avatar">JD</div><div className="min-w-0 flex-1"><div className="profile-name">Jordan Davis</div><div className="profile-role">Store administrator</div></div><button className="icon-button" aria-label="Profile options"><ChevronDown size={16} /></button></div>
        </div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <div className="flex items-center gap-3"><button className="icon-button mobile-menu" onClick={() => setMobileNavOpen(true)} aria-label="Open menu"><Menu size={20} /></button><div className="breadcrumb"><span>Workspace</span><span className="breadcrumb-slash">/</span><strong>{contentTitle}</strong></div></div>
          <div className="topbar-actions">
            <label className="search-box"><Search size={16} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search orders..." aria-label="Search orders" /><kbd>⌘ K</kbd></label>
            <button className={`icon-button refresh-button ${refreshing ? 'spin-once' : ''}`} onClick={() => loadData(true)} aria-label="Refresh data"><RefreshCw size={17} /></button>
            <button className="icon-button notification-button" onClick={() => setActive('notifications')} aria-label="View notifications"><Bell size={18} />{notifications.length > 0 && <i />}</button>
            <div className="topbar-divider" /><div className="topbar-date">Today, {new Date().toLocaleDateString([], { month: 'short', day: 'numeric' })}</div>
          </div>
        </header>

        <div className="page-content">
          {error && <div className="error-banner"><div className="error-symbol">!</div><div><strong>Couldn’t load everything</strong><p>{error}</p></div><button className="error-retry" onClick={() => loadData(true)}>Try again</button></div>}
          {active === 'overview' && <Overview {...{ busy, products, orders: filteredOrders, notifications, totalRevenue, confirmedOrders, lowStock, onNewOrder: () => setOrderOpen(true), onRefresh: () => loadData(true), onSelectOrder: showOrder, refreshing }} />}
          {active === 'orders' && <OrdersPage {...{ orders: filteredOrders, busy, onNewOrder: () => setOrderOpen(true), onSelectOrder: showOrder }} />}
          {active === 'inventory' && <InventoryPage {...{ products, busy }} />}
          {active === 'payments' && <PaymentsPage {...{ orders, busy, onSelectOrder: showOrder }} />}
          {active === 'notifications' && <NotificationsPage {...{ notifications, busy }} />}
        </div>
        <footer className="footer"><span>Northstar Commerce</span><span className="footer-dot">·</span><span>Connected through API Gateway</span><span className="footer-spacer" /><span>RRPE microservices demo</span></footer>
      </main>

      {orderOpen && <CreateOrderModal products={products} onClose={() => setOrderOpen(false)} onCreated={(order) => { setOrderOpen(false); setActive('orders'); notify(`Order #${order.id} was created successfully.`); loadData(true); }} onError={(message, isError = true) => notify(message, isError ? 'error' : 'success')} />}
      {selectedOrder && <OrderDrawer order={selectedOrder} payments={payments} onClose={() => setSelectedOrder(null)} />}
      {toast && <div className={`toast ${toast.kind === 'error' ? 'toast-error' : ''}`}><span className="toast-check">{toast.kind === 'error' ? <X size={15} /> : <Check size={15} />}</span>{toast.message}<button onClick={() => setToast(null)} aria-label="Dismiss"><X size={15} /></button></div>}
    </div>
  );
}

function PageHeading({ eyebrow, title, subtitle, action }) {
  return <div className="page-heading"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1><p>{subtitle}</p></div>{action}</div>;
}

function Overview({ busy, products, orders, notifications, totalRevenue, confirmedOrders, lowStock, onNewOrder, onRefresh, onSelectOrder, refreshing }) {
  const today = new Date().toLocaleDateString('en-US', { weekday: 'long', day: '2-digit', month: 'long', year: 'numeric' }).toUpperCase();
  return <>
    <PageHeading eyebrow={today} title="Good morning, Jordan" subtitle="Here’s what’s happening with your store today." action={<button className="button button-primary" onClick={onNewOrder}><Plus size={17} /> Create order</button>} />
    <div className="welcome-strip"><div className="welcome-icon"><Sparkles size={20} /></div><div><strong>Your store is looking good.</strong><span> A quick snapshot of orders, stock, and customer activity.</span></div><button onClick={onRefresh} disabled={refreshing}>{refreshing ? 'Updating…' : 'Refresh data'}<ArrowRight size={15} /></button></div>
    <div className="stat-grid">
      <StatCard label="Total orders" value={busy ? '—' : orders.length} note={`${confirmedOrders} confirmed`} icon={ShoppingBag} tone="sage" trend="+12.8%" trendUp />
      <StatCard label="Sales value" value={busy ? '—' : money(totalRevenue)} note="From confirmed orders" icon={Activity} tone="violet" trend="+8.2%" trendUp />
      <StatCard label="Products in stock" value={busy ? '—' : products.length} note={lowStock ? `${lowStock} item${lowStock === 1 ? '' : 's'} running low` : 'Stock levels are healthy'} icon={Box} tone="amber" trend={lowStock ? 'Review' : 'Healthy'} trendUp={!lowStock} />
      <StatCard label="Notifications" value={busy ? '—' : notifications.length} note="Messages sent to customers" icon={Bell} tone="blue" trend="Live" trendUp />
    </div>
    <div className="dashboard-grid">
      <section className="panel orders-panel"><div className="panel-heading"><div><h2>Recent orders</h2><p>Your latest customer activity</p></div><button className="text-link" onClick={() => window.dispatchEvent(new CustomEvent('navigate', { detail: 'orders' }))}>View all <ArrowRight size={15} /></button></div>
        {busy ? <LoadingRows /> : orders.length ? <div className="table-scroll"><table className="data-table"><thead><tr><th>ORDER</th><th>CUSTOMER</th><th>DATE</th><th>AMOUNT</th><th>STATUS</th><th /></tr></thead><tbody>{orders.slice(0, 6).map((order) => <tr key={order.id} onClick={() => onSelectOrder(order)} className="clickable-row"><td><span className="order-number">#{order.id}</span></td><td><span className="customer-cell"><span className="customer-avatar">{String(order.customerId || 'C').slice(0, 1).toUpperCase()}</span>{order.customerId}</span></td><td className="muted-cell">{dateTime(order.createdAt)}</td><td className="amount-cell">{money(order.totalAmount)}</td><td><StatusPill value={order.status} /></td><td><ArrowRight size={15} className="row-arrow" /></td></tr>)}</tbody></table></div> : <EmptyState icon={ShoppingBag} title="No orders yet" copy="Your new orders will show up here." />}
      </section>
      <section className="panel inventory-panel"><div className="panel-heading"><div><h2>Stock overview</h2><p>Availability across your catalog</p></div><button className="round-link" onClick={() => window.dispatchEvent(new CustomEvent('navigate', { detail: 'inventory' }))}><ArrowRight size={16} /></button></div>
        {busy ? <LoadingRows count={3} /> : products.length ? <div className="stock-list">{products.slice(0, 4).map((product, index) => <ProductStockRow key={product.sku} product={product} index={index} />)}</div> : <EmptyState icon={Box} title="No products found" copy="Inventory will appear when the service is online." />}
      </section>
    </div>
    <div className="bottom-grid">
      <section className="panel flow-panel"><div className="panel-heading"><div><h2>Order journey</h2><p>How a new order moves through your system</p></div><span className="live-label"><span /> LIVE FLOW</span></div><div className="flow-steps"><FlowStep icon={ShoppingBag} label="Order placed" detail="Customer checks out" tone="green" /><div className="flow-line" /><FlowStep icon={Box} label="Stock reserved" detail="Inventory checked" tone="blue" /><div className="flow-line" /><FlowStep icon={CreditCard} label="Payment" detail="Charge processed" tone="violet" /><div className="flow-line" /><FlowStep icon={Bell} label="Confirmation" detail="Customer notified" tone="amber" /></div></section>
      <section className="tip-card"><div className="tip-orb tip-orb-one" /><div className="tip-orb tip-orb-two" /><div className="tip-top"><span className="tip-icon"><Sparkles size={17} /></span><span>QUICK TIP</span></div><h3>Try the full order flow</h3><p>Create an order to see stock reservation, payment, and customer notification working together.</p><button onClick={onNewOrder}>Create your first order <ArrowRight size={15} /></button><div className="tip-art"><ShoppingCart size={63} strokeWidth={1.2} /></div></section>
    </div>
  </>;
}

function StatCard({ label, value, note, icon: Icon, tone, trend, trendUp }) {
  return <div className="stat-card"><div className="stat-top"><div className={`stat-icon icon-${tone}`}><Icon size={18} strokeWidth={1.9} /></div><span className={`trend-chip ${trendUp ? 'trend-positive' : 'trend-neutral'}`}>{trendUp ? <ArrowUpRight size={13} /> : <ArrowDownRight size={13} />}{trend}</span></div><div className="stat-value">{value}</div><div className="stat-label">{label}</div><div className="stat-note">{note}</div><div className={`stat-decoration deco-${tone}`} /></div>;
}

function ProductStockRow({ product, index }) {
  const available = Number(product.availableQuantity || 0);
  const total = Math.max(Number(product.stockQuantity || 0), 1);
  const percentage = Math.min(100, Math.round((available / total) * 100));
  const low = available <= 5;
  const palettes = ['product-sage', 'product-lilac', 'product-peach', 'product-blue'];
  return <div className="stock-row"><div className={`product-mini ${palettes[index % palettes.length]}`}><Package size={17} /></div><div className="stock-meta"><div className="stock-name">{product.name}</div><div className="stock-sku">{product.sku}</div><div className="stock-bar"><span className={low ? 'bar-low' : ''} style={{ width: `${percentage}%` }} /></div></div><div className={`stock-quantity ${low ? 'quantity-low' : ''}`}>{available}<span> / {total}</span></div></div>;
}

function FlowStep({ icon: Icon, label, detail, tone }) { return <div className="flow-step"><div className={`flow-icon flow-${tone}`}><Icon size={17} /></div><strong>{label}</strong><span>{detail}</span></div>; }
function StatusPill({ value }) { return <span className={`status-pill ${statusTone(value)}`}><i />{String(value || 'Unknown').replaceAll('_', ' ')}</span>; }

function OrdersPage({ orders, busy, onNewOrder, onSelectOrder }) {
  return <><PageHeading eyebrow="ORDER MANAGEMENT" title="Orders" subtitle="Track every order from checkout to delivery." action={<button className="button button-primary" onClick={onNewOrder}><Plus size={17} /> Create order</button>} />
    <section className="panel full-panel"><div className="panel-heading"><div><h2>All orders</h2><p>{orders.length} order{orders.length === 1 ? '' : 's'} in your workspace</p></div><span className="table-count"><span className="system-pulse" /> Updated just now</span></div>
      {busy ? <LoadingRows count={5} /> : orders.length ? <div className="table-scroll"><table className="data-table"><thead><tr><th>ORDER</th><th>CUSTOMER</th><th>ITEMS</th><th>CREATED</th><th>TOTAL</th><th>STATUS</th><th /></tr></thead><tbody>{orders.map((order) => <tr key={order.id} onClick={() => onSelectOrder(order)} className="clickable-row"><td><span className="order-number">#{order.id}</span></td><td><span className="customer-cell"><span className="customer-avatar">{String(order.customerId || 'C').slice(0, 1).toUpperCase()}</span>{order.customerId}</span></td><td>{order.items?.length || 0} item{order.items?.length === 1 ? '' : 's'}</td><td className="muted-cell">{dateTime(order.createdAt)}</td><td className="amount-cell">{money(order.totalAmount)}</td><td><StatusPill value={order.status} /></td><td><ArrowRight size={15} className="row-arrow" /></td></tr>)}</tbody></table></div> : <EmptyState icon={ShoppingBag} title="Your order list is empty" copy="Create an order and it will appear here." action={<button className="button button-primary button-small" onClick={onNewOrder}><Plus size={15} /> Create order</button>} />}
    </section></>;
}

function InventoryPage({ products, busy }) {
  return <><PageHeading eyebrow="CATALOG & STOCK" title="Inventory" subtitle="A live view of product availability across your store." /><div className="inventory-summary"><div><span>PRODUCTS</span><strong>{busy ? '—' : products.length}</strong></div><div><span>TOTAL AVAILABLE</span><strong>{busy ? '—' : products.reduce((sum, product) => sum + Number(product.availableQuantity || 0), 0)}</strong></div><div><span>NEEDS RESTOCK</span><strong className={products.some((p) => Number(p.availableQuantity) <= 5) ? 'text-amber-600' : ''}>{busy ? '—' : products.filter((p) => Number(p.availableQuantity) <= 5).length}</strong></div></div>
    {busy ? <LoadingRows count={4} /> : products.length ? <div className="catalog-grid">{products.map((product, index) => <ProductCard key={product.sku} product={product} index={index} />)}</div> : <section className="panel"><EmptyState icon={Box} title="Inventory is unavailable" copy="Check that the API gateway and Inventory service are running." /></section>}</>;
}

function ProductCard({ product, index }) {
  const available = Number(product.availableQuantity || 0);
  const palettes = ['card-sage', 'card-lilac', 'card-peach', 'card-blue'];
  const illustrations = ['⌨', '◉', '▣', '◖'];
  return <article className="catalog-card"><div className={`catalog-art ${palettes[index % palettes.length]}`}><span>{illustrations[index % illustrations.length]}</span><div className="catalog-art-grid" /><span className="sku-tag">{product.sku}</span></div><div className="catalog-body"><div className="catalog-title-row"><div><h3>{product.name}</h3><p>SKU · {product.sku}</p></div><span className="catalog-price">{money(product.price)}</span></div><div className="catalog-stock-row"><span className={`stock-status ${available <= 5 ? 'stock-status-low' : ''}`}><i />{available <= 5 ? 'Low stock' : 'In stock'}</span><span>{available} available</span></div><div className="catalog-progress"><span className={available <= 5 ? 'bar-low' : ''} style={{ width: `${Math.min(100, (available / Math.max(Number(product.stockQuantity), 1)) * 100)}%` }} /></div><div className="catalog-foot"><span>{product.reservedQuantity || 0} reserved</span><span>{product.stockQuantity} total units</span></div></div></article>;
}

function PaymentsPage({ orders, busy, onSelectOrder }) {
  const confirmed = orders.filter((order) => String(order.status).toUpperCase() === 'CONFIRMED' && order.paymentId);
  const failures = orders.filter((order) => String(order.status).toUpperCase().includes('PAYMENT_FAILED'));
  return <><PageHeading eyebrow="TRANSACTION CENTER" title="Payments" subtitle="Payment activity is created as part of the order checkout flow." />
    <div className="stat-grid stat-grid-two"><StatCard label="Successful charges" value={busy ? '—' : confirmed.length} note="Confirmed orders with a payment" icon={CreditCard} tone="sage" trend="Settled" trendUp /><StatCard label="Declined payments" value={busy ? '—' : failures.length} note="Orders where payment did not complete" icon={Activity} tone="amber" trend="Review" trendUp={false} /></div>
    <section className="panel full-panel"><div className="panel-heading"><div><h2>Payment activity</h2><p>Open an order to view its payment records.</p></div></div>{busy ? <LoadingRows count={4} /> : orders.length ? <div className="table-scroll"><table className="data-table"><thead><tr><th>ORDER</th><th>CUSTOMER</th><th>PAYMENT ID</th><th>DATE</th><th>AMOUNT</th><th>ORDER STATUS</th><th /></tr></thead><tbody>{orders.map((order) => <tr key={order.id} className="clickable-row" onClick={() => onSelectOrder(order)}><td><span className="order-number">#{order.id}</span></td><td>{order.customerId}</td><td>{order.paymentId ? <span className="payment-id">PMT-{order.paymentId}</span> : <span className="muted-cell">Not charged</span>}</td><td className="muted-cell">{dateTime(order.createdAt)}</td><td className="amount-cell">{money(order.totalAmount)}</td><td><StatusPill value={order.status} /></td><td><ArrowRight size={15} className="row-arrow" /></td></tr>)}</tbody></table></div> : <EmptyState icon={CreditCard} title="No payment activity" copy="Payments will appear here after customers place orders." />}</section></>;
}

function NotificationsPage({ notifications, busy }) {
  return <><PageHeading eyebrow="CUSTOMER MESSAGES" title="Notifications" subtitle="Confirmation messages sent after successful orders." />
    <section className="panel full-panel"><div className="panel-heading"><div><h2>Sent notifications</h2><p>{notifications.length} message{notifications.length === 1 ? '' : 's'} recorded</p></div><span className="live-label"><span /> ACTIVITY</span></div>{busy ? <LoadingRows count={4} /> : notifications.length ? <div className="notification-list">{[...notifications].reverse().map((notice) => <article className="notification-row" key={notice.id}><div className="notice-avatar"><Bell size={17} /></div><div className="notice-main"><div className="notice-title-row"><strong>{notice.channel || 'Customer update'} <span>· Order #{notice.orderId}</span></strong><span className="notice-time">{dateTime(notice.sentAt)}</span></div><p>{notice.message}</p><div className="notice-recipient">To <strong>{notice.recipient}</strong></div></div><span className="status-pill status-green"><i />Sent</span></article>)}</div> : <EmptyState icon={Bell} title="No notifications yet" copy="Customer messages sent by the order flow will appear here." />}</section></>;
}

function CreateOrderModal({ products, onClose, onCreated, onError }) {
  const [customerId, setCustomerId] = useState('');
  const [cart, setCart] = useState({});
  const [forceFail, setForceFail] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [localError, setLocalError] = useState('');
  const cartItems = products.filter((product) => Number(cart[product.sku]) > 0);
  const total = cartItems.reduce((sum, product) => sum + Number(product.price) * Number(cart[product.sku]), 0);
  const canSubmit = customerId.trim() && cartItems.length && !submitting;

  function setQuantity(sku, next) {
    setCart((current) => ({ ...current, [sku]: Math.max(0, Math.min(99, Number(next) || 0)) }));
  }

  async function submit(event) {
    event.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true); setLocalError('');
    try {
      const order = await createOrder({ customerId: customerId.trim(), items: cartItems.map((product) => ({ sku: product.sku, quantity: Number(cart[product.sku]) })), forcePaymentFail: forceFail });
      onCreated(order);
    } catch (error) {
      const message = readableError(error);
      setLocalError(message);
      onError(message);
    } finally { setSubmitting(false); }
  }

  return <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><section className="order-modal" role="dialog" aria-modal="true" aria-labelledby="create-order-title"><div className="modal-header"><div><span className="eyebrow">CHECKOUT FLOW</span><h2 id="create-order-title">Create an order</h2><p>Choose products and we’ll take care of the rest.</p></div><button className="icon-button" onClick={onClose} aria-label="Close"><X size={19} /></button></div><form onSubmit={submit}>
    <label className="form-label" htmlFor="customer-id">Customer ID</label><input id="customer-id" className="form-input" value={customerId} onChange={(event) => setCustomerId(event.target.value)} placeholder="e.g. cust-1042" required autoFocus />
    <div className="form-section-title"><span>SELECT PRODUCTS</span><small>{products.length} available</small></div>
    <div className="modal-product-list">{products.map((product, index) => { const quantity = Number(cart[product.sku] || 0); const stock = Number(product.availableQuantity || 0); return <div className="modal-product" key={product.sku}><div className={`product-mini product-tone-${index % 4}`}><Package size={17} /></div><div className="modal-product-info"><strong>{product.name}</strong><span>{money(product.price)} · {stock} available</span></div><div className="quantity-control"><button type="button" aria-label={`Remove one ${product.name}`} disabled={!quantity} onClick={() => setQuantity(product.sku, quantity - 1)}>−</button><span>{quantity}</span><button type="button" aria-label={`Add one ${product.name}`} disabled={quantity >= stock} onClick={() => setQuantity(product.sku, quantity + 1)}>+</button></div></div>; })}</div>
    <label className="force-fail-row"><input type="checkbox" checked={forceFail} onChange={(event) => setForceFail(event.target.checked)} /><span className="custom-check"><Check size={12} /></span><span><strong>Simulate payment decline</strong><small>For demonstrating the stock release flow</small></span><span className="demo-tag">DEMO</span></label>
    {localError && <div className="form-error">{localError}</div>}
    <div className="modal-footer"><div><span>Order total</span><strong>{money(total)}</strong></div><button className="button button-primary" type="submit" disabled={!canSubmit}>{submitting ? <><LoaderCircle className="animate-spin" size={16} /> Processing…</> : <>Place order <ArrowRight size={16} /></>}</button></div>
    </form></section></div>;
}

function OrderDrawer({ order, payments, onClose }) {
  const lines = order.items || [];
  return <div className="drawer-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><aside className="order-drawer" role="dialog" aria-modal="true"><div className="drawer-header"><div><div className="eyebrow">ORDER DETAILS</div><h2>Order <span>#{order.id}</span></h2></div><button className="icon-button" onClick={onClose} aria-label="Close order details"><X size={19} /></button></div><div className="drawer-status"><StatusPill value={order.status} /><span>Created {dateTime(order.createdAt)}</span></div><div className="drawer-section"><div className="drawer-section-heading"><span>Customer</span></div><div className="drawer-customer"><span className="customer-avatar customer-avatar-large">{String(order.customerId || 'C').slice(0, 1).toUpperCase()}</span><div><strong>{order.customerId}</strong><small>Customer ID</small></div></div></div><div className="drawer-section"><div className="drawer-section-heading"><span>Items</span><span>{lines.length} item{lines.length === 1 ? '' : 's'}</span></div><div className="drawer-items">{lines.map((item) => <div className="drawer-item" key={item.sku}><div><strong>{item.sku}</strong><small>Qty {item.quantity} × {money(item.unitPrice)}</small></div><b>{money(Number(item.unitPrice) * Number(item.quantity))}</b></div>)}{!lines.length && <p className="drawer-muted">Item details were not included in this response.</p>}</div><div className="drawer-total"><span>Total</span><strong>{money(order.totalAmount)}</strong></div></div>{order.failureReason && <div className="failure-note"><strong>Order note</strong><span>{order.failureReason}</span></div>}<div className="drawer-section"><div className="drawer-section-heading"><span>Payment</span></div>{payments.length ? payments.map((payment) => <div className="payment-detail" key={payment.paymentId}><div className="payment-detail-icon"><CreditCard size={17} /></div><div className="min-w-0 flex-1"><strong>Payment #{payment.paymentId}</strong><small>{payment.status} · {dateTime(payment.createdAt)}</small></div><b>{money(payment.amount)}</b></div>) : <div className="drawer-empty-line">{order.paymentId ? 'Loading payment details…' : 'No payment was recorded for this order.'}</div>}</div><div className="drawer-bottom"><div className="drawer-flow"><span><i className="flow-dot flow-dot-green" />Order submitted</span><span><i className="flow-dot flow-dot-blue" />Inventory checked</span><span><i className="flow-dot flow-dot-violet" />Payment {order.paymentId ? 'processed' : 'not completed'}</span><span><i className="flow-dot flow-dot-amber" />Notification sent when order succeeds</span></div><button className="button button-secondary w-full" onClick={onClose}>Done</button></div></aside></div>;
}

function LoadingRows({ count = 4 }) { return <div className="loading-rows">{Array.from({ length: count }).map((_, index) => <div className="skeleton-row" key={index}><span /><span /><span /><span /></div>)}</div>; }
function EmptyState({ icon: Icon, title, copy, action }) { return <div className="empty-state"><div className="empty-icon"><Icon size={21} /></div><strong>{title}</strong><p>{copy}</p>{action}</div>; }

export default App;
