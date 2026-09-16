import {useCallback, useEffect, useRef, useState} from 'react';

export function useClock() {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  return now;
}
export function useResource(loader, dependencies) {
  const [state, setState] = useState({data: null, loading: true, error: ''});
  const [revision, setRevision] = useState(0);
  const latest = useRef(loader);
  latest.current = loader;
  const reload = useCallback(() => setRevision(value => value + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setState({data: null, loading: true, error: ''});
    Promise.resolve().then(() => latest.current(controller.signal))
      .then(data => { if (!controller.signal.aborted) setState({data, loading: false, error: ''}); })
      .catch(error => { if (!controller.signal.aborted) setState({data: null, loading: false, error: error.message}); });
    return () => controller.abort();
  }, [...dependencies, revision]);
  return {...state, reload};
}
