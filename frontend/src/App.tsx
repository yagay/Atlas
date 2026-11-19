/**
 * Atlas JAV-only frontend entry.
 * Keep the original search hooks, GitHub OAuth, auth session and original Android package.
 * Remove unrelated routes rather than just hiding their nav links.
 */
import React, { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from '@/services/api/queryClient';
import { useAuthStore, useThemeStore, useLanguageStore } from '@/stores';
import { apiClient } from '@/services/api';
import i18next from '@/i18n';
import { NativeShell } from '@/components/layout/NativeShell';
import { RouteScrollRestoration } from '@/components/layout/RouteScrollRestoration';
import { PullToRefreshProvider } from '@/components/layout/PullToRefresh';
import { ToastContainer } from '@/components/ui/Toast';
import { MainSearchPage } from '@/pages/MainSearchPage';
import { JavSettingsPage } from '@/pages/JavSettingsPage';
import { LoginPage, RegisterPage, ForgotPasswordPage, GitHubCallbackPage, GoogleCallbackPage } from '@/pages/auth';
import { ErrorBoundary } from '@/components/ErrorBoundary';

const AuthGate: React.FC<{ children: React.ReactNode; guestOnly?: boolean }> = ({ children, guestOnly = false }) => {
  const { isAuthenticated, isLoading } = useAuthStore();
  if (isLoading) {
    return <div className="flex min-h-screen items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-amber-500" />
    </div>;
  }
  if (guestOnly && isAuthenticated) return <Navigate to="/main?tab=jav" replace />;
  if (!guestOnly && !isAuthenticated) return <Navigate to="/login" replace />;
  return <>{children}</>;
};

const App: React.FC = () => {
  const { setUser, setLoading, logout } = useAuthStore();
  const { resolvedTheme } = useThemeStore();
  const language = useLanguageStore(s => s.language);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', resolvedTheme === 'dark');
  }, [resolvedTheme]);

  useEffect(() => {
    if (i18next.language !== language) void i18next.changeLanguage(language);
  }, [language]);

  useEffect(() => {
    const initAuth = async () => {
      setLoading(true);
      const token = await useAuthStore.getState().restoreToken();
      if (!token) {
        logout();
        setLoading(false);
        return;
      }
      try {
        const response = await apiClient.get<{ success: boolean; data: import('@/types').User }>('/auth/me');
        if (response.success && response.data) setUser(response.data);
        else logout();
      } catch (err: unknown) {
        // Do not discard a valid session on a temporary network outage.
        if ((err as { status?: number })?.status === 401) logout();
      } finally {
        setLoading(false);
      }
    };
    void initAuth();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <NativeShell />
        <RouteScrollRestoration />
        <PullToRefreshProvider>
          <ErrorBoundary>
            <Routes>
              <Route path="/" element={<Navigate to="/main?tab=jav" replace />} />
              <Route path="/main" element={<AuthGate><MainSearchPage /></AuthGate>} />
              <Route path="/settings" element={<AuthGate><JavSettingsPage /></AuthGate>} />
              <Route path="/login" element={<AuthGate guestOnly><LoginPage /></AuthGate>} />
              <Route path="/register" element={<AuthGate guestOnly><RegisterPage /></AuthGate>} />
              <Route path="/forgot-password" element={<AuthGate guestOnly><ForgotPasswordPage /></AuthGate>} />
              <Route path="/auth/callback" element={<GitHubCallbackPage />} />
              <Route path="/auth/google/callback" element={<GoogleCallbackPage />} />
              {/* Legacy URLs cannot re-open anime/movie/community/dashboard/admin pages. */}
              <Route path="*" element={<Navigate to="/main?tab=jav" replace />} />
            </Routes>
          </ErrorBoundary>
          <ToastContainer />
        </PullToRefreshProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
};

export default App;
